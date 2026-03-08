#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include "encoding.h"

#define CLINT_BASE 0x02000000UL
#define CLINT_MSIP(hart) ((volatile uint32_t *)(CLINT_BASE + 4UL * (hart)))

static const size_t n_cores = N_CORES;
const char* hello_str = "Hello from hart %u\n"; // Do not change - used in CI/CD tests

/*
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

    if (__sync_add_and_fetch(&barrier_count, 1) == n_cores) {
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

/*
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

/*
 * Wake all secondary harts by raising their software interrupt.
 * Hart 0 is the primary hart, so we start at hart 1.
 */
static void wakeup_other_harts(size_t n_harts) {
    for (size_t hart = 1; hart < n_harts; ++hart) {
        *CLINT_MSIP(hart) = 1;
        __sync_synchronize();
    }
}

/*
 * Entry point for secondary harts.
 *
 * Each secondary hart:
 *  1. reads its hart ID
 *  2. clears its own pending software interrupt
 *  3. prints safely
 *  4. waits until all harts have printed
 *  5. returns
 */
void __main(void) {
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

    return;
}

/*
 * Primary hart entry point.
 *
 * Hart 0 prints, wakes the other harts, waits until every hart has printed,
 * then returns.
 */
int main(void) {
    uint32_t hart_id = 0;

    safe_print_hart(hart_id);

    wakeup_other_harts(n_cores);

    /*
     * Wait until all harts, including hart 0, have printed.
     */
    barrier();

    return 0;
}