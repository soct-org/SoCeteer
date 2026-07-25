/*
 * Delay shim for the vendored Xilinx sources. Everything in this module runs in a
 * sleepable worker, so the busy-wait the bare-metal shim needs becomes a real sleep.
 */
#ifndef SOCT_SLEEP_H
#define SOCT_SLEEP_H

#include <linux/delay.h>

static inline int usleep(unsigned long useconds)
{
    usleep_range(useconds, useconds + useconds / 8 + 10);
    return 0;
}

#endif /* SOCT_SLEEP_H */
