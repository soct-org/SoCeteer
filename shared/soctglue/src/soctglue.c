#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "soct/soctglue.h"
#include "soct/syscall-handler.h"
#include "soct/smoldtb.h"
#include "soct/defaults.h"

#include "syscall-uart.h"
#include "syscall-sdc.h"
#include "syscall-htif.h"

static uint8_t s_dtb_tree[SOCT_DTB_MAX_SIZE];
static size_t s_dtb_parsed_len = 0;

// Boot sync barrier — released by primary, waited on by secondaries
static volatile int __boot_sync = 0;


volatile sc_htif_slot_t tohost __attribute__((section(SOCT_HTIF_SECTION))) = 0;
volatile sc_htif_slot_t fromhost __attribute__((section(SOCT_HTIF_SECTION))) = 0;
spinlock_t htif_lock = SOCT_SPINLOCK_INIT;

extern char **environ;

extern int main(int argc, char *argv[], char *envp[]);

extern int __main(int argc, char *argv[], char *envp[]);

static uintptr_t s_args[SOCT_ARG_MAX];
static int s_argc = 0;
static char **s_argv = NULL;

static char *s_setup_msgs[SOCT_N_SETUP_MSGS];
static size_t s_setup_msgs_cnt = 0;

static char *s_soct_out_of_err_msgs = "Out of error message slots, recompile with increased SOCT_N_ERROR_MSGS";


void __init_tls(void) {
    register char *__thread_self __asm__ ("tp");
    extern char __tdata_start[];
    extern char __tbss_offset[];
    extern char __tdata_size[];
    extern char __tbss_size[];

    memcpy(__thread_self, __tdata_start, (size_t) __tdata_size);
    memset(__thread_self + (size_t) __tbss_offset, 0, (size_t) __tbss_size);
}

void *dtb_malloc(const size_t length) {
    if (s_dtb_parsed_len + length > SOCT_DTB_MAX_SIZE) {
        return NULL;
    }
    void *ptr = &s_dtb_tree[s_dtb_parsed_len];
    s_dtb_parsed_len += length;
    return ptr;
}


void dtb_free(void *ptr, const size_t length) {
    (void) ptr;
    (void) length;
    memset(ptr, 0, length);
    s_dtb_parsed_len -= length;
}

/**
 * Add a message to the setup log. The setup log is a list of messages that are printed at the end of the setup phase, before entering main. This can be used by handlers to report errors during setup, such as DTB parsing errors.
 * @param msg A message to add to the setup log.
 */
void soct_add_setup_msg(const char *msg) {
    if (s_setup_msgs_cnt >= SOCT_N_SETUP_MSGS) {
        return; // No more slots left, just drop the error
    }
    if (s_setup_msgs_cnt == SOCT_N_SETUP_MSGS - 1) {
        s_setup_msgs[s_setup_msgs_cnt++] = s_soct_out_of_err_msgs;
        return;
    }
    s_setup_msgs[s_setup_msgs_cnt++] = strdup(msg);
}


void dtb_on_error(const char *msg) {
    soct_add_setup_msg(msg);
}


void dump_msgs(void) {
    if (s_setup_msgs_cnt == 0) {
        return;
    }
    printf("++ Soctglue log begin ++\n");
    for (size_t i = 0; i < s_setup_msgs_cnt; i++) {
        printf("  - %s\n", s_setup_msgs[i]);
    }
    printf("++ Soctglue log end ++\n");
}


void _soct_start_secondary(void) {
    // Secondary harts wait for all handlers to be registered and the primary to release the boot barrier before entering main
    while (__boot_sync == 0)
        __asm__ volatile("" ::: "memory");
    __sync_synchronize();

    exit(__main(s_argc, s_argv, environ));
}

/**
 *
 * @param hartid The hartid of the current hart.
 * @param dtb_blob The pointer to the dtb blob passed by crt0.S. This is expected to be a valid dtb blob or null.
 */
void _soct_start_main(int hartid, void *dtb_blob) {
    char welcome_msg[128];
    snprintf(welcome_msg, sizeof(welcome_msg), "Soctglue initialized from hart %d with dtb blob at %p", hartid,
             dtb_blob);
    soct_add_setup_msg(welcome_msg);

    const dtb_ops ops = {
        .malloc = dtb_malloc,
        .free = dtb_free,
        .on_error = dtb_on_error
    };
    dtb_init((uintptr_t) dtb_blob, ops);

    if (soct_init_from_dtb_uart()) {
        soct_register_default_handler((soct_handler_t){
            .handle = soct_handle_uart,
        });
    }

    if (soct_init_from_dtb_sdc()) {
        soct_register_default_handler((soct_handler_t){
            .handle = soct_handle_sdc,
        });
    }

    if (soct_htif_present()) {
        soct_add_setup_msg("HTIF device detected, registering handler");
        soct_register_default_handler((soct_handler_t){
            .handle = soct_handle_htif,
        });

        const long ret = soct_syscall(SOCT_GET_MAINVARS, (uintptr_t) s_args, sizeof(s_args), 0, 0, 0, 0, 0);
        if (ret != 0) {
            char err_msg[128];
            snprintf(err_msg, sizeof(err_msg), "Failed to get main vars from HTIF syscall, ret code %ld", ret);
            soct_add_setup_msg(err_msg);
        } else {
            s_argc = (int) s_args[0];
            s_argv = (char **) &s_args[1];
            soct_add_setup_msg("Successfully retrieved main vars from HTIF syscall");
        }
    }

    soct_add_setup_msg("Initialization complete, entering main");
    dump_msgs();

    // Release secondary harts
    __sync_synchronize();
    __boot_sync = -1;
    __sync_synchronize();

    exit(main(s_argc, s_argv, environ));
}


size_t soct_hart_id(void) {
    size_t hart_id;
    asm volatile ("csrr %0, mhartid" : "=r"(hart_id));
    return hart_id;
}


int soct_n_harts(void) {
    dtb_node *cpus = dtb_find("/cpus");
    if (!cpus)
        return 1;

    size_t count = 0;
    dtb_node *child = dtb_get_child(cpus);
    while (child) {
        dtb_prop *dtype = dtb_find_prop(child, "device_type");
        if (dtype) {
            const char *val = dtb_read_prop_string(dtype, 0);
            if (val && __builtin_strcmp(val, "cpu") == 0)
                count++;
        }
        child = dtb_get_sibling(child);
    }
    return count > 0 ? (int) count : -1;
}

