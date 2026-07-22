/*
 * Instrumentation.
 *
 * The bring-up telemetry reads identical between working and "no video input"
 * runs, so these watch the parts it cannot see: the sink's own view of the
 * link, and the video-out lock bit sampled at high rate instead of once per
 * frame at a fixed phase (where it aliases with per-frame events). The
 * controlled experiment at the bottom is what settled why the lock was being
 * lost at all.
 */
#ifndef DP_TEST_DIAG_H
#define DP_TEST_DIAG_H

#include <stdint.h>

#include "xdppsu.h"

/* Video pipeline status GPIO (vidstat0 node), all at offset 0. The locked bit
 * is the definitive "video is leaving the PL" signal; underflow means the
 * stream starves. */
#define VIDSTAT_LOCKED    0x1u
#define VIDSTAT_UNDERFLOW 0x2u
#define VIDSTAT_OVERFLOW  0x4u

extern volatile uint32_t *video_status;

/* Hand the instrumentation its two handles; call before anything below. */
void probe_init(XDpPsu *dp, volatile uint32_t *vidstat);

/* DPCD link/sink status - the MONITOR's view of the link, i.e. the ground
 * truth behind a "no video input" OSD.
 *
 * @return the sink's receiving-video bit, or -1 when even the AUX read fails
 *         (the link itself is down, not just the stream)
 */
int dp_sink_status(const char *tag);

/* Tight-poll the lock bit for `ms` milliseconds; reports falling edges and
 * the unlocked fraction. */
void probe_lock(const char *tag, unsigned ms);

/* One-shot bring-up check: prove each clock/data domain is alive after enable. */
void verify_pipeline(uintptr_t vdma_base, uintptr_t vtc_base);

/* Controlled experiment: does memory pressure or the park-pointer write break
 * the video lock? Compiles to nothing when built with -DRUN_LOCK_EXPERIMENT=0. */
#ifndef RUN_LOCK_EXPERIMENT
#define RUN_LOCK_EXPERIMENT 1
#endif

void run_experiment(uintptr_t vdma_base);

#endif /* DP_TEST_DIAG_H */
