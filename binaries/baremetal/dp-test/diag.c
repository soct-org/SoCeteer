#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "diag.h"
#include "dp.h"
#include "dptest.h"
#include "sleep.h"
#include "video.h"
#include "xil_io.h"

/* =========================================================================
 * Sink status and the lock waveform
 * ========================================================================= */

volatile uint32_t *video_status;

static XDpPsu *s_dp;

void probe_init(XDpPsu *dp, volatile uint32_t *vidstat) {
    s_dp = dp;
    video_status = vidstat;
}

/* Prints per-lane status (bit0 clock recovery, bit1 channel EQ, bit2 symbol
 * lock; lane 1 in bits 4..6), interlane alignment, and the sink's "receiving
 * video" bit. DPCD 0x202 LANE0_1_STATUS, 0x204 LANE_ALIGN_STATUS, 0x205
 * SINK_STATUS (bit0: receive port 0 sees an active stream). */
int dp_sink_status(const char *tag) {
    u8 st[4] = {0, 0, 0, 0};
    if (XDpPsu_AuxRead(s_dp, 0x202, 4, st) != XST_SUCCESS) {
        printf("[dp %s] AUX READ FAILED - the link itself is down, not just the stream\n", tag);
        return -1;
    }
    const int rx = st[3] & 1;
    printf("[dp %s] lane01=0x%02x align=0x%02x sink_receiving_video=%d msa_en=%lu intr=0x%08lx\n",
           tag, st[0], st[2], rx,
           (unsigned long) Xil_In32(PS_DP_BASEADDR + XDPPSU_ENABLE_MAIN_STREAM),
           (unsigned long) XDpPsu_ReadReg(PS_DP_BASEADDR, XDPPSU_INTR_STATUS));
    return rx;
}

void probe_lock(const char *tag, unsigned ms) {
    unsigned long samples = 0, edges = 0, low = 0;
    uint32_t prev = video_status[0] & VIDSTAT_LOCKED;
    const unsigned long t0 = cycles_now();
    const unsigned long limit = us_to_cycles((unsigned long) ms * 1000ul);
    unsigned long t1;
    do {
        const uint32_t v = video_status[0] & VIDSTAT_LOCKED;
        if (prev && !v) edges++;
        if (!v) low++;
        prev = v;
        samples++;
        t1 = cycles_now();
    } while (t1 - t0 < limit);
    printf("[lock %s] %ums: %lu samples, %lu falling edges, unlocked %lu%%\n",
           tag, ms, samples, edges, samples ? (100ul * low) / samples : 0ul);
}

/* =========================================================================
 * Bring-up check: prove each clock/data domain is alive after enable
 * ========================================================================= */

void verify_pipeline(uintptr_t vdma_base, uintptr_t vtc_base) {
    const uint32_t flags = video_status[0];
    printf("Video-out status: locked=%u underflow=%u overflow=%u\n",
           flags & VIDSTAT_LOCKED, (flags & VIDSTAT_UNDERFLOW) >> 1,
           (flags & VIDSTAT_OVERFLOW) >> 2);
    if (!(flags & VIDSTAT_LOCKED)) {
        printf("  BAD: the video out is NOT locked - no video leaves the PL.\n");
    }

    /* Clear the sticky status bits, then watch for a few frame times. */
    vtc_clear_status(vtc_base);
    vdma_clear_frame_done(vdma_base);
    usleep(100000); /* 100 ms = ~6 frames at 60 Hz */
    const uint32_t isr = vtc_status(vtc_base);
    const uint32_t sr = vdma_status(vdma_base);
    printf("Pipeline check after 100 ms: VTC ISR=0x%08" PRIx32 ", VDMA MM2S_DMASR=0x%08" PRIx32 "\n",
           isr, sr);

    if (sr & VDMA_DMASR_ERR_ALL) {
        printf("  BAD: VDMA reports errors (internal/slave/decode/size) - frame reads "
            "from DRAM are failing.\n");
    }
    if (vtc_saw_vblank(vtc_base)) {
        printf("  OK: VTC is generating frames - the pixel clock (ClkWiz) is alive "
            "and the timing generator runs.\n");
    } else {
        printf("  BAD: VTC generated no vertical blanking - the pixel clock is dead "
            "or the generator is misprogrammed; nothing can be displayed.\n");
    }
    if (sr & VDMA_DMASR_FRMCNT_IRQ) {
        printf("  OK: VDMA completed full frames - the stream is being consumed by "
            "the video out.\n");
    } else {
        printf("  BAD: VDMA completed no frame - the video out is not consuming the "
            "stream (no lock); the monitor shows the background color.\n");
    }

    /* PS side: interrupt status bits set regardless of the mask (write-1-clear),
     * and together they localize where the live timing stops:
     *   EXT_VSYNC_TS   - posedge seen on the external (PL) vsync wire itself
     *   PIXEL0/1_MATCH - the early timing counters passed the (zero) match value
     *   VBLNK_START    - the stream timing entered vertical blanking
     *   VSYNC_TS       - a vsync event on the (internal) video stream
     * Clear everything, wait ~6 frames, then read the fresh events. */
    XDpPsu_WriteReg(PS_DP_BASEADDR, XDPPSU_INTR_STATUS, 0xFFFFFFFFu);
    usleep(100000);
    const u32 intr = XDpPsu_ReadReg(PS_DP_BASEADDR, XDPPSU_INTR_STATUS);
    printf("DP INTR_STATUS after 100 ms (all bits cleared first): 0x%08" PRIx32 "\n", intr);
    printf("  external (PL) vsync edges seen:   %s\n",
           (intr & XDPPSU_INTR_EXT_VSYNC_TS_MASK) ? "YES" : "no");
    printf("  early timing counters running:    %s\n",
           (intr & (XDPPSU_INTR_PIXEL0_MATCH_MASK | XDPPSU_INTR_PIXEL1_MATCH_MASK))
               ? "YES"
               : "no");
    printf("  stream vertical blanking events:  %s\n",
           (intr & XDPPSU_INTR_VBLNK_START_MASK) ? "YES" : "no");
    printf("  stream vsync timestamp events:    %s\n",
           (intr & XDPPSU_INTR_VSYNC_TS_MASK) ? "YES" : "no");
    printf("DP main stream enable readback: %" PRIu32 "\n",
           (u32) Xil_In32(PS_DP_BASEADDR + XDPPSU_ENABLE_MAIN_STREAM));

    /* Real frame delivery rate: count frame-complete events over one second.
     * 60/s = the memory path sustains the pixel rate; well below 60 = the
     * DMA read path is the bottleneck (underflow is starvation, not
     * misalignment). One event per 10 ms window is enough up to 100 fps. */
    unsigned frames = 0;
    for (int i = 0; i < 100; i++) {
        usleep(10000);
        frames += (unsigned) vdma_take_frame_done(vdma_base);
    }
    printf("VDMA frame delivery rate: %u frames/s (60 needed for real-time)\n", frames);

    /* Lock behavior over time: never locked = alignment problem; toggling =
     * lock gained then lost to FIFO starvation. */
    printf("Lock/underflow samples over 2 s (L=locked U=underflow): ");
    for (int i = 0; i < 20; i++) {
        const uint32_t f = video_status[0];
        printf("%c%c ", (f & VIDSTAT_LOCKED) ? 'L' : '-', (f & VIDSTAT_UNDERFLOW) ? 'U' : '-');
        usleep(100000);
    }
    printf("\n");
}

/* =========================================================================
 * Controlled experiment: does memory pressure or the park-pointer write break
 * the video lock?
 *
 * The observational data pointed at flips (single-buffer rendering showed 0%
 * unlocked under identical traffic, and a 4x line buffer changed nothing), so
 * this varies each factor on its own, within ONE bitstream - comparing across
 * bitstreams is what produced the wrong answer the first time:
 *   A  memory pressure ramp, no flips        - pressure innocent?
 *   B  flip-rate ramp, no rendering          - flips guilty?
 *   C  "null flips" (PARK_PTR rewritten with the SAME store), no rendering
 *      - is it the buffer switch, or merely touching the register?
 *
 * Verdict from hardware: pressure is the cause, flips are innocent. Phase A
 * also reports the VDMA's achieved frame rate, which is what makes starvation
 * directly visible rather than inferred.
 * ========================================================================= */

#if RUN_LOCK_EXPERIMENT

static uint64_t *s_stress; /* >> L2, so every touch misses to DRAM */
static size_t s_stress_words;

/* Sample the lock bit for `ms`, touching `lines` 64-byte lines (read-modify-
 * write = read miss + dirty writeback) between samples. Reports the achieved
 * DRAM bandwidth beside the lock statistics, so lock loss can be correlated
 * with real, measured pressure instead of assumed pressure. */
static void probe_pressure(unsigned ms, unsigned lines, uintptr_t vdma_base) {
    unsigned long samples = 0, low = 0, edges = 0, touched = 0, vframes = 0;
    size_t idx = 0;
    uint32_t prev = video_status[0] & VIDSTAT_LOCKED;
    vdma_clear_frame_done(vdma_base); /* clear stale frame flag */
    const unsigned long t0 = cycles_now();
    const unsigned long limit = us_to_cycles((unsigned long) ms * 1000ul);
    unsigned long t1;
    do {
        for (unsigned i = 0; i < lines; i++) {
            idx += 8; /* 64-byte stride: one cache line */
            if (idx >= s_stress_words) idx = 0;
            s_stress[idx] += 1u;
            touched++;
        }
        const uint32_t v = video_status[0] & VIDSTAT_LOCKED;
        if (prev && !v) edges++;
        if (!v) low++;
        prev = v;
        samples++;
        /* Frame completions: the VDMA's ACHIEVED throughput. If this falls
         * below 60/s as pressure rises, starvation is proven directly - the
         * fetch path, not the video out, is what gives way. */
        vframes += (unsigned long) vdma_take_frame_done(vdma_base);
        t1 = cycles_now();
    } while (t1 - t0 < limit);
    const unsigned long us = cycles_to_us(t1 - t0);
    printf("[A pressure] cpu %4lu MB/s -> vdma %3lu fps (%4lu MB/s), unlocked %3lu%%\n",
           us ? (touched * 64ul) / us : 0ul,
           us ? (vframes * 1000000ul) / us : 0ul,
           us ? (vframes * (unsigned long) fb_size()) / us : 0ul,
           samples ? (100ul * low) / samples : 0ul);
}

/* Sample the lock bit for `ms` while flipping at `fps` (0 = never), with NO
 * rendering and NO memory pressure. `null_only` rewrites PARK_PTR with the
 * store already selected: the register write and its side effects, without an
 * actual buffer switch. */
static void probe_flips(unsigned ms, unsigned fps, uintptr_t vdma_base, int null_only) {
    unsigned long samples = 0, low = 0, edges = 0, flips = 0;
    uint32_t prev = video_status[0] & VIDSTAT_LOCKED;
    const unsigned long t0 = cycles_now();
    const unsigned long limit = us_to_cycles((unsigned long) ms * 1000ul);
    const unsigned long period = fps ? us_to_cycles(1000000ul / fps) : 0;
    unsigned long next = t0 + period;
    unsigned long t1 = t0;
    do {
        if (fps && t1 >= next) {
            if (null_only) vdma_park(vdma_base, fb_front());
            else fb_flip(vdma_base);
            flips++;
            next += period;
        }
        const uint32_t v = video_status[0] & VIDSTAT_LOCKED;
        if (prev && !v) edges++;
        if (!v) low++;
        prev = v;
        samples++;
        t1 = cycles_now();
    } while (t1 - t0 < limit);
    printf("[%s] %2u/s target (%3lu done): %6lu samples, %3lu falling edges, unlocked %3lu%%\n",
           null_only ? "C nullflip" : "B flips   ", fps, flips, samples, edges,
           samples ? (100ul * low) / samples : 0ul);
}

void run_experiment(uintptr_t vdma_base) {
    const size_t bytes = 8u << 20; /* 8 MB >> 512 KB L2: guaranteed DRAM traffic */
    s_stress = malloc(bytes);
    if (!s_stress) {
        printf("FATAL: stress buffer allocation failed\n");
        abort();
    }
    s_stress_words = bytes / sizeof(uint64_t);
    memset(s_stress, 0, bytes);

    printf("\n=== EXPERIMENT: memory pressure vs. park-pointer flips ===\n");
    printf("A: rising DRAM pressure, NO flips - CPU MB/s vs the VDMA's achieved fps\n");
    const unsigned ramp[] = {0, 1, 2, 4, 8, 16, 64, 256};
    for (unsigned i = 0; i < sizeof(ramp) / sizeof(ramp[0]); i++) {
        probe_pressure(500, ramp[i], vdma_base);
    }
    printf("B: rising flip rate, NO rendering, NO pressure (are flips the cause?)\n");
    const unsigned rates[] = {0, 1, 5, 15, 30, 60};
    for (unsigned i = 0; i < sizeof(rates) / sizeof(rates[0]); i++) {
        probe_flips(500, rates[i], vdma_base, 0);
    }
    printf("C: same rates, but PARK_PTR rewritten with the SAME store\n");
    for (unsigned i = 0; i < sizeof(rates) / sizeof(rates[0]); i++) {
        probe_flips(500, rates[i], vdma_base, 1);
    }
    dp_sink_status("post-experiment");
    printf("=== EXPERIMENT done ===\n\n");
    free(s_stress);
}

#else /* !RUN_LOCK_EXPERIMENT */

void run_experiment(uintptr_t vdma_base) {
    (void) vdma_base;
}

#endif /* RUN_LOCK_EXPERIMENT */
