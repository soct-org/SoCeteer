#include <stdio.h>
#include <stdint.h>
#include "encoding.h"
#define CLINT_BASE 0x2000000UL

static size_t n_cores = 2;

static void __attribute__((noinline)) barrier() {
    static volatile int sense;
    static volatile int count;
    static __thread int threadsense;

    __sync_synchronize();

    threadsense = !threadsense;
    if (__sync_fetch_and_add(&count, 1) == (int) n_cores - 1) {
        count = 0;
        sense = threadsense;
    } else
        while (sense != threadsense) {}

    __sync_synchronize();
}

void __main(void) {
    // This is the entry point secondary harts.
    uint32_t hart_id;
    asm volatile ("csrr %0, mhartid" : "=r"(hart_id)); // Read hart ID from mhartid CSR
    for (size_t i = 0; i < n_cores; i++) {
        if (hart_id == i) {
            printf("Hello world from core %u\n", hart_id);
        }
        barrier();
    }

    if (hart_id > 0) while (1) {}
}

void wakeup_other_harts(size_t n_harts) {
    volatile uint32_t *msip = (volatile uint32_t *)CLINT_BASE;
    for (size_t hart = 1; hart < n_harts; ++hart) {
        msip[hart] = 1; // Set MSIP for hart 'hart' to 1 (trigger interrupt)
        // Optionally poll until the hart acknowledges (if required by your system)
        while (msip[hart] != 1) { /* wait for write to take effect */ }
    }
}

int main(void) {
    // This is the entry point for the primary hart.
    printf("Hello from the primary hart!\n");
    wakeup_other_harts(n_cores);

    __main(); // Call the secondary hart main function
    return 0;
}
