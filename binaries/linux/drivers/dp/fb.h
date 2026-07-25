/* SPDX-License-Identifier: GPL-2.0 */
/*
 * The framebuffer device of incoherent-fetch designs (fb.c). Coherent designs
 * never call these: their framebuffer is simplefb's, via the /chosen node.
 */
#ifndef SOCT_DP_FB_H
#define SOCT_DP_FB_H

#include <linux/types.h>

/* Maps the carve-out and the L2 flush register, clears the frame and makes the
 * clear visible. Call before the scanout starts fetching. */
int soct_dp_fb_prepare(phys_addr_t fb, resource_size_t fb_size, u32 width, u32 height);

/* Registers the fbdev (the console binds to it). Call once the display is up. */
int soct_dp_fb_register(void);

/* Safe in every state, including after a failed prepare. */
void soct_dp_fb_teardown(void);

#endif
