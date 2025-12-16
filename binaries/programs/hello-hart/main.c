#include <stdio.h>
#include <stdint.h>
#include "encoding.h"
#define CLINT_BASE 0x2000000UL

// To disassemble - can help find race conditions:
// /soceteer/shared/vendor/riscv-none-elf-gcc/bin/riscv-none-elf-objdump -D -M no-aliases -S -C elfs/programs/hello-hart/boot-sim.elf > elfs/programs/hello-hart/boot-sim.disasm.S

static size_t n_cores = 2;
const char* hello_str = "Hello from hart %u\n"; // Do not change - used in CI/CD tests

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
    volatile uint32_t hart_id;

    asm volatile ("csrr %0, mhartid" : "=r"(hart_id)); // Read hart ID from mhartid CSR
    printf(hello_str, hart_id);

    if (hart_id > 0) while (1) {}
}

void wakeup_other_harts(size_t n_harts) {
    volatile uint32_t *msip = (volatile uint32_t *)CLINT_BASE;
    for (size_t hart = 1; hart < n_harts; ++hart) {
        msip[hart] = 1; // Set MSIP for hart 'hart' to 1 (trigger interrupt)
        while (msip[hart] != 1) { /* wait for write to take effect */ }
    }
}

int main(void) {
    // This is the entry point for the primary hart.
    wakeup_other_harts(n_cores);

    //__main(); // Call the secondary hart main function
    while (1) {}
    return 0;
}
