/* SPDX-License-Identifier: GPL-2.0 */
/*
 * The video-mode interface between the pipeline (dp-main.c) and the framebuffer
 * device (fb.c). There is no mode list: a mode is any complete timing whose pixel
 * clock the design's MMCM can synthesize within the advertised budget - whether a
 * monitor accepts it is the monitor's call, which no table can predict.
 */
#ifndef SOCT_DP_DP_H
#define SOCT_DP_DP_H

#include <linux/string.h>
#include <linux/types.h>

/* One complete timing (field names follow the kernel's `display-timings` binding)
 * plus the pixel clock it implies. All fields are u32 on purpose: equality is
 * memcmp ([[soct_dp_mode_equal]]). */
struct soct_dp_mode {
	u32 hactive, hfront_porch, hsync_len, hback_porch;
	u32 vactive, vfront_porch, vsync_len, vback_porch;
	u32 hsync_active, vsync_active; /* 1 = positive polarity */
	u32 fps;
	u32 pixel_clock_hz;
};

static inline bool soct_dp_mode_equal(const struct soct_dp_mode *a,
				      const struct soct_dp_mode *b)
{
	return !memcmp(a, b, sizeof(*a));
}

/* The mode the pipeline is scanning right now. */
const struct soct_dp_mode *soct_dp_current_mode(void);

/* Whether this design can serve `m`: the clock must be solvable by the pixel MMCM
 * and within the ceiling timing closure ran at (static designs only reproduce
 * their one mode). Rewrites pixel_clock_hz to the exactly-achieved value; requests
 * a rounding-hair above the ceiling clamp onto it. */
int soct_dp_validate_mode(struct soct_dp_mode *m);

/* Retunes the whole pipeline to `m`: halts the VDMA, reprograms the pixel MMCM,
 * the timing generator and the DisplayPort stream, restarts the scanout. No-op
 * when `m` is already current. The framebuffer content is not touched - the
 * caller repaints for the new geometry. */
int soct_dp_switch_mode(const struct soct_dp_mode *m);

#endif
