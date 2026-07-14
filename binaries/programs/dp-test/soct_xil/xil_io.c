#include "xil_io.h"

#include <stdio.h>
#include <stdlib.h>

static uintptr_t s_ps_base;
static uintptr_t s_window_base;
static uintptr_t s_window_size;

void SoctXil_SetPsWindow(uintptr_t ps_base, uintptr_t window_base, uintptr_t window_size) {
    if (window_size == 0) {
        printf("SoctXil_SetPsWindow: zero window size\n");
        abort();
    }
    s_ps_base = ps_base;
    s_window_base = window_base;
    s_window_size = window_size;
}

/* Translate a PS-space register address into the mapped window; PL addresses
 * (below the PS range) pass through. Fail hard on anything unmapped - a wrong
 * register address must never turn into a silent bus error or a stray write. */
static volatile u32 *translate(UINTPTR addr) {
    if (s_window_size == 0) {
        printf("Xil_In32/Out32: SoctXil_SetPsWindow has not been called\n");
        abort();
    }
    if (addr >= s_ps_base && addr - s_ps_base < s_window_size) {
        return (volatile u32 *)(addr - s_ps_base + s_window_base);
    }
    if (addr < 0x80000000ul) { /* PL peripheral range of the MMIO port */
        return (volatile u32 *)addr;
    }
    printf("Xil_In32/Out32: address 0x%lx is outside the PS window (PS base 0x%lx, "
           "size 0x%lx) and not a PL peripheral\n",
           (unsigned long)addr, (unsigned long)s_ps_base, (unsigned long)s_window_size);
    abort();
}

u32 Xil_In32(UINTPTR Addr) {
    return *translate(Addr);
}

void Xil_Out32(UINTPTR Addr, u32 Value) {
    *translate(Addr) = Value;
}
