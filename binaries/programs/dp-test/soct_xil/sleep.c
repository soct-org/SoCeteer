#include "sleep.h"

#include <stdio.h>
#include <stdlib.h>

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
    const unsigned long target = (unsigned long)useconds * s_cycles_per_us;
    while (read_cycles() - start < target) {}
    return 0;
}
