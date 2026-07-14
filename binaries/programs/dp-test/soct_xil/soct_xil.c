/*
 * Implementation of the shim layer that lets the vendored Xilinx sources in
 * ../xilinx compile unmodified on the SoCeteer RISC-V toolchain. The header
 * FILE NAMES in this directory are fixed - the vendored code includes them by
 * name (xil_io.h, sleep.h, xvidc.h, ...) - but the implementations are small,
 * so they all live here.
 */
#include <stdio.h>
#include <stdlib.h>

#include "sleep.h"
#include "xil_io.h"
#include "xvidc.h"

/* =========================================================================
 * xil_io: register access with PS-address window translation (see xil_io.h)
 * ========================================================================= */

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
        return (volatile u32 *) (addr - s_ps_base + s_window_base);
    }
    if (addr < 0x80000000ul) { /* PL peripheral range of the MMIO port */
        return (volatile u32 *) addr;
    }
    printf("Xil_In32/Out32: address 0x%lx is outside the PS window (PS base 0x%lx, "
           "size 0x%lx) and not a PL peripheral\n",
           (unsigned long) addr, (unsigned long) s_ps_base, (unsigned long) s_window_size);
    abort();
}

u32 Xil_In32(UINTPTR Addr) {
    return *translate(Addr);
}

void Xil_Out32(UINTPTR Addr, u32 Value) {
    *translate(Addr) = Value;
}

/* =========================================================================
 * sleep: usleep as a cycle-counter busy-wait (see sleep.h)
 * ========================================================================= */

static unsigned long s_cycles_per_us;

unsigned long SoctXil_GetCyclesPerUs(void) {
    return s_cycles_per_us;
}

void SoctXil_SetCpuFreqHz(unsigned long hz) {
    s_cycles_per_us = hz / 1000000ul;
    if (s_cycles_per_us == 0) {
        printf("SoctXil_SetCpuFreqHz: implausible cpu frequency %lu Hz\n", hz);
        abort();
    }
}

static unsigned long read_cycles(void) {
    unsigned long cycles;
    __asm__ volatile("csrr %0, mcycle" : "=r"(cycles));
    return cycles;
}

int usleep(useconds_t useconds) {
    if (s_cycles_per_us == 0) {
        printf("usleep: SoctXil_SetCpuFreqHz has not been called\n");
        abort();
    }
    const unsigned long start = read_cycles();
    const unsigned long target = (unsigned long) useconds * s_cycles_per_us;
    while (read_cycles() - start < target) {}
    return 0;
}

/* =========================================================================
 * xvidc: the timing table for the supported video modes (see xvidc.h)
 * ========================================================================= */

/* Timing values taken from video_common's xvidc_timings_table.c (CEA-861/DMT). */
const XVidC_VideoTimingMode XVidC_VideoTimingModes[XVIDC_VM_NUM_SUPPORTED] = {
    {XVIDC_VM_640x480_60_P, "640x480@60Hz", XVIDC_FR_60HZ,
     {640, 16, 96, 48, 800, 0, 480, 10, 2, 33, 525, 0, 0, 0, 0, 0}},
    {XVIDC_VM_1280x720_60_P, "1280x720@60Hz", XVIDC_FR_60HZ,
     {1280, 110, 40, 220, 1650, 1, 720, 5, 5, 20, 750, 0, 0, 0, 0, 1}},
    {XVIDC_VM_1920x1080_60_P, "1920x1080@60Hz", XVIDC_FR_60HZ,
     {1920, 88, 44, 148, 2200, 1, 1080, 4, 5, 36, 1125, 0, 0, 0, 0, 1}},
};

u64 XVidC_GetPixelClockHzByVmId(XVidC_VideoMode VmId) {
    if (VmId >= XVIDC_VM_NUM_SUPPORTED) {
        printf("XVidC_GetPixelClockHzByVmId: unsupported video mode %d\n", (int) VmId);
        abort();
    }
    const XVidC_VideoTiming *t = &XVidC_VideoTimingModes[VmId].Timing;
    return (u64) t->HTotal * t->F0PVTotal * XVidC_VideoTimingModes[VmId].FrameRate;
}
