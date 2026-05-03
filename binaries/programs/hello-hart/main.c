#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>

#include "soct/syscall-handler.h"

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
 * Assumes hart IDs are dense in [0, N_CORES - 1].
 */
static volatile uint32_t barrier_count = 0;
static volatile uint32_t barrier_sense = 0;
static volatile uint32_t local_sense[N_CORES] = {0};

static void __attribute__((noinline)) barrier(void) {
    uint32_t hart_id;
    asm volatile ("csrr %0, mhartid" : "=r"(hart_id));

    local_sense[hart_id] ^= 1U;
    __sync_synchronize();

    if (__sync_add_and_fetch(&barrier_count, 1) == N_CORES) {
        barrier_count = 0;
        __sync_synchronize();
        barrier_sense = local_sense[hart_id];
    } else {
        while (barrier_sense != local_sense[hart_id]) {
            asm volatile ("" ::: "memory");
        }
    }

    __sync_synchronize();
}

/**
 * Global console lock.
 *
 * printf is typically not multicore-safe on bare-metal, so every hart must
 * serialize access to it.
 */
static volatile uint32_t print_lock = 0;

static void lock_print(void) {
    while (__sync_lock_test_and_set(&print_lock, 1)) {
        asm volatile ("" ::: "memory");
    }
    __sync_synchronize();
}

static void unlock_print(void) {
    __sync_synchronize();
    __sync_lock_release(&print_lock);
}

static void safe_print_hart(uint32_t hart_id) {
    lock_print();
    printf(hello_str, hart_id);
    unlock_print();
}

/**
 * Wake all secondary harts by raising their software interrupt.
 * Hart 0 is the primary hart, so we start at hart 1.
 */
static void wakeup_other_harts() {
    for (size_t hart = 1; hart < N_CORES; ++hart) {
        *CLINT_MSIP(hart) = 1;
        __sync_synchronize();
    }
}

/**
 * Entry point for secondary harts.
 *
 * Each secondary hart:
 *  1. reads its hart ID
 *  2. clears its own pending software interrupt
 *  3. prints safely
 *  4. waits until all harts have printed
 *  5. returns
 */
int __main(int argc, char **argv, char *envp[]) {
    uint32_t hart_id;
    asm volatile ("csrr %0, mhartid" : "=r"(hart_id));

    /*
     * Clear our MSIP bit now that we have woken up.
     * Leaving it set may keep the software interrupt pending.
     */
    *CLINT_MSIP(hart_id) = 0;
    __sync_synchronize();

    safe_print_hart(hart_id);

    /*
     * Do not return until every hart has completed its print.
     * No print order is enforced; only completion is synchronized.
     */
    barrier();
    return 0;
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
         printf("Intercepted SOCT_EXIT with code %d - passing it on to other handlers\n", (int) a0);
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

    // This is how you can add custom syscall handlers:
    soct_register_handler((soct_handler_t){
        .handle = handle_exit,
    });

    safe_print_hart(hart_id);

    wakeup_other_harts();

    /*
     * Wait until all harts, including hart 0, have printed.
     */
    barrier();

    return 0;
}
