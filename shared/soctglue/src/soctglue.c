#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "syscall-handler.h"
#include "soct/smoldtb.h"
#include "soct/defaults.h"

#include "syscall-uart.h"
#include "syscall-sdc.h"
#include "syscall-htif.h"

static uint8_t s_dtb_tree[SOCT_DTB_MAX_SIZE];
static size_t s_dtb_parsed_len = 0;

static char *s_setup_errors[SOCT_N_ERROR_MSGS];
static size_t s_setup_error_count = 0;

// Boot sync barrier — released by primary, waited on by secondaries
static volatile int __boot_sync = 0;


volatile uint64_t tohost __attribute__((section(SOCT_HTIF_SECTION))) = 0;
volatile uint64_t fromhost __attribute__((section(SOCT_HTIF_SECTION))) = 0;
spinlock_t htif_lock = SOCT_SPINLOCK_INIT;
extern char **environ;

extern int main(int argc, char *argv[], char *envp[]);
extern int __main(int argc, char *argv[], char *envp[]);

static uintptr_t s_args[SOCT_ARG_MAX];
static int s_argc = 0;
static char **s_argv = NULL;

// Error messages
static char *s_soct_out_of_err_msgs = "Out of error message slots, recompile with increased SOCT_N_ERROR_MSGS\n";


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


void soct_add_setup_error(const char *msg) {
    if (s_setup_error_count >= SOCT_N_ERROR_MSGS) {
        return; // No more slots left, just drop the error
    }
    if (s_setup_error_count == SOCT_N_ERROR_MSGS - 1) {
        s_setup_errors[s_setup_error_count++] = s_soct_out_of_err_msgs;
        return;
    }
    s_setup_errors[s_setup_error_count++] = strdup(msg);
}


void dtb_on_error(const char *msg) {
    soct_add_setup_error(msg);
}


void dump_errors(void) {
    if (s_setup_error_count == 0) {
        return;
    }
    fprintf(stderr, "Errors during Soctglue setup:\n");
    for (size_t i = 0; i < s_setup_error_count; i++) {
        fprintf(stderr, "  - %s\n", s_setup_errors[i]);
    }
}


void _soct_start_secondary(void) {
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
    // TODO For now, this is the path for the testchipip bootrom where a0 and a1 are not hartid/dtb_blob
    // TODO Ideally we want to also read the dtb blob to allow htif to handle only a subset of syscalls
    if (soct_htif_present()) {
        soct_handler_resp_t resp = {.status = SOCT_HANDLER_PASS, .ret = 0};
        soct_handle_htif(&resp, SOCT_HTIF_DEV_TEST, 0, 0, 0, 0, 0, 0, 0);
        soct_register_handler((soct_handler_t){
            .handle = soct_handle_htif,
        });

        long ret = soct_syscall(SOCT_GET_MAINVARS, (uintptr_t) s_args, sizeof(s_args), 0, 0, 0, 0, 0);
        if (ret != 0) exit((int) ret);

        s_argc = (int)s_args[0];
        s_argv = (char **)&s_args[1];

        // Release secondary harts
        __sync_synchronize();
        __boot_sync = -1;
        __sync_synchronize();

        exit(main(s_argc, s_argv, environ));
    }

    if (dtb_blob == NULL) {
        soct_add_setup_error("No DTB blob provided, skipping DTB parsing and initialization");
        dump_errors();
        return;
    }

    if (hartid != 0) {
        dump_errors();
        return;
    }

    const dtb_ops ops = {
        .malloc = dtb_malloc,
        .free = dtb_free,
        .on_error = dtb_on_error
    };
    dtb_init((uintptr_t) dtb_blob, ops);

    if (soct_init_from_dtb_uart()) {
        soct_register_handler((soct_handler_t){
            .handle = soct_handle_uart,
        });
    }

   if (soct_init_from_dtb_sdc()) {
       soct_register_handler((soct_handler_t){
           .handle = soct_handle_sdc,
       });
    }

    dump_errors();

    // Release secondary harts
    __sync_synchronize();
    __boot_sync = -1;
    __sync_synchronize();

    exit(main(0, NULL, environ));
}
