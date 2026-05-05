#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <stdarg.h>


#include "soct/syscall-handler.h"
#include "soct/defaults.h"
#include "soct/soctglue.h"
#include "atomic.h"

#ifdef SOCT_CLINT_BASE
#define CLINT_MSIP(hart) ((volatile uint32_t *)(SOCT_CLINT_BASE + 4UL * (hart)))
#else
#error "SOCT_CLINT_BASE is not defined. Please define it to the base address of the CLINT in your system."
#endif

const char *hello_str = "Hello from hart %u\n";

/**
 * Reusable sense-reversing barrier.
 *
 * Each hart toggles its local sense and increments a shared arrival count.
 * The last hart to arrive resets the count and flips the global sense,
 * releasing everyone else.
 *
 * Assumes hart IDs are dense in [0, soct_get_num_harts() - 1].
 */
static volatile uint32_t barrier_count = 0;
static volatile uint32_t barrier_sense = 0;
static volatile uint32_t local_sense[SOCT_MAX_HARTS] = {0};
static size_t n_harts = 0;

/*
 * Global console lock logic:
 *
 * printf is typically not multicore-safe on bare-metal, so every hart must
 * serialize access to it.
 */
static atomic_t print_lock = 0;

static void lock_print(void) {
    while (atomic_swap_acquire(&print_lock, 1)) {
        rmb();
    }
}

static void unlock_print(void) {
    atomic_clear_release(&print_lock);
}

// multithreaded printf
int mprintf(const char *format, ...) {
    lock_print();
    va_list args;
    va_start(args, format);
    const int res = vprintf(format, args);
    va_end(args);
    unlock_print();
    return res;
}

/**
 * A reusable sense-reversing barrier.
 * Each hart toggles its local sense and increments a shared arrival count.
 * The last hart to arrive resets the count and flips the global sense,
 * releasing everyone else.
 */
static void __attribute__((noinline)) barrier(void) {
    const size_t hart_id = soct_hart_id();

    local_sense[hart_id] ^= 1U;
    mb();

    if (__sync_add_and_fetch(&barrier_count, 1) == (uint32_t) n_harts) {
        barrier_count = 0;
        mb();
        barrier_sense = local_sense[hart_id];
    } else {
        while (barrier_sense != local_sense[hart_id]) {
            rmb();
        }
    }

    mb();
}


/**
 * Wake all secondary harts by raising their software interrupt.
 * Hart 0 is the primary hart, so we start at hart 1.
 */
static void wakeup_other_harts(void) {
    for (size_t hart = 1; hart < n_harts; ++hart) {
        *CLINT_MSIP(hart) = 1;
        wmb();
    }
}

/**
 * Entry point for secondary harts.
 *
 * Each secondary hart:
 *  1. Waits until the primary hart raises its MSIP via wakeup_other_harts()
 *  2. Clears its MSIP to acknowledge the wakeup
 *  3. Prints a message
 *  4. Waits at the barrier until all harts have printed
 *  5. Wait for interrupt
 */
int __attribute__ ((noreturn)) __main(int argc, char **argv, char *envp[]) {
    const size_t hart_id = soct_hart_id();

    /* Wait until hart 0 raises our MSIP via wakeup_other_harts() */
    while (!*CLINT_MSIP(hart_id)) rmb();

    /* Clear MSIP now that we've observed it */
    *CLINT_MSIP(hart_id) = 0;
    mb();

    mprintf(hello_str, (unsigned) hart_id);
    barrier(); // All harts arrive here, hart 0 exits, all others wfi
    for (;;)
        wfi();
}

static void handle_exit(
    soct_handler_resp_t *resp,
    const sc_type_t syscall,
    const sc_arg_t a0,
    const sc_arg_t a1,
    const sc_arg_t a2,
    const sc_arg_t a3,
    const sc_arg_t a4,
    const sc_arg_t a5,
    const sc_arg_t a6) {
    (void) a1;
    (void) a2;
    (void) a3;
    (void) a4;
    (void) a5;
    (void) a6;
    if (syscall == SOCT_EXIT) {
        mprintf("Hart %u exiting with code %u\n", (unsigned) soct_hart_id(), (unsigned) a0);
    }
    resp->status = SOCT_HANDLER_PASS;
}


/**
 * Primary hart entry point.
 *
 * Hart 0 prints, wakes the other harts, waits until every hart has printed,
 * then returns.
 */
int main(int argc, char **argv, char *envp[]) {
    uint32_t hart_id = 0;

    const int n_harts_opt = soct_n_harts();
    if (n_harts_opt < 0) {
        mprintf("Failed to get number of harts from Soctglue\n");
        return -1;
    }
    n_harts = (size_t) n_harts_opt;
    mprintf("Detected %zu harts\n", n_harts);


    // This is how you can add custom syscall handlers:
    soct_register_handler((soct_handler_t){
        .handle = handle_exit,
    });

    mprintf(hello_str, (unsigned) hart_id);

    wakeup_other_harts();

    /*
     * Wait until all harts, including hart 0, have printed.
     */
    barrier();

    return 0;
}
