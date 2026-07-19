/*
 * Delay shim for the vendored Xilinx sources: usleep as a cycle-counter busy-wait
 * (declaration comes from unistd.h, implementation in sleep.c). The
 * cycles-per-microsecond rate comes from the DTB (cpu clock-frequency);
 * SoctXil_SetCpuFreqHz must be called before the first delay.
 */
#ifndef SOCT_SLEEP_H
#define SOCT_SLEEP_H

#include <unistd.h>

void SoctXil_SetCpuFreqHz(unsigned long hz);

unsigned long SoctXil_GetCyclesPerUs(void);

#endif /* SOCT_SLEEP_H */
