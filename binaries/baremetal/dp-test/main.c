/* =========================================================================
 * DisplayPort bring-up test
 *
 * Drives the full video pipeline of a design built with soct.WithVideoStream
 * (or soct.WithIncoherentVideoStream): framebuffer (DRAM) -> AXI VDMA ->
 * AXI4-Stream video out (+ VTC timing) -> PS DP live video input -> DP main
 * link -> monitor.
 *
 * This file is the program's flow: discover the pipeline in the device tree,
 * start it stage by stage, then run the demo. The stages themselves live next
 * door:
 *
 *   dptest.h   device-tree accessors (implemented here) and the time base
 *   video.h    framebuffers, VDMA, timing generator - the whole PL side
 *   dp.h       PS window probe, AVBuf routing, DP link and stream
 *   render.h   the test pattern and the teapot
 *   diag.h     lock/sink instrumentation, the bring-up check, the experiment
 *
 * Prerequisite: the PS must have been initialized once after power-up
 * (clocks, DP SERDES/PS-GTR lanes) by running the psu_init that Vivado
 * generates alongside the design, e.g. in xsdb:
 *     targets -set -filter {name =~ "PSU"}
 *     source .../soctvivado_project.gen/.../psu_init.tcl ; psu_init
 * Without it the DP PHY never reports ready.
 * ========================================================================= */

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "diag.h"
#include "dp.h"
#include "dptest.h"
#include "render.h"
#include "sleep.h"
#include "video.h"

/* =========================================================================
 * Device-tree accessors (declared in dptest.h)
 * ========================================================================= */

dtb_node *dt_require_compatible(const char *compat) {
    dtb_node *node = dtb_find_compatible(NULL, compat);
    if (!node) {
        printf("FATAL: no device-tree node with compatible \"%s\" - was the "
               "design built with --with-config soct.WithVideoStream?\n", compat);
        abort();
    }
    return node;
}

void dt_require_reg(dtb_node *node, uintptr_t *base, uintptr_t *size) {
    dtb_prop *reg = dtb_find_prop(node, "reg");
    if (!reg) {
        printf("FATAL: device-tree node has no reg property\n");
        abort();
    }
    dtb_pair layout = {dtb_get_addr_cells_for(node), dtb_get_size_cells_for(node)};
    dtb_pair val = {0, 0};
    if (dtb_read_prop_2(reg, layout, &val) < 1) {
        printf("FATAL: could not read device-tree reg property\n");
        abort();
    }
    *base = (uintptr_t) val.a;
    if (size) *size = (uintptr_t) val.b;
}

unsigned long dt_require_u32(dtb_node *node, const char *name) {
    dtb_prop *prop = dtb_find_prop(node, name);
    uintmax_t val = 0;
    if (!prop || dtb_read_prop_1(prop, 1, &val) < 1) {
        printf("FATAL: could not read device-tree property \"%s\"\n", name);
        abort();
    }
    return (unsigned long) val;
}

/* Map the window through which the vendored Xilinx sources reach the PS
 * registers (see xil_io.h), then prove the path actually responds. */
static void open_ps_window(void) {
    dtb_node *win = dt_require_compatible("soct,zynqmp-dp-window");
    uintptr_t win_base, win_size;
    dt_require_reg(win, &win_base, &win_size);
    const uintptr_t ps_base = (uintptr_t) dt_require_u32(win, "soct,ps-base");
    SoctXil_SetPsWindow(ps_base, win_base, win_size);
    printf("PS window: 0x%lx..0x%lx -> PS 0x%lx\n", (unsigned long) win_base,
           (unsigned long) (win_base + win_size - 1), (unsigned long) ps_base);
    dp_probe_ps_window();
}

/* =========================================================================
 * The animation, and the pipeline health monitor that runs alongside it
 *
 * Three modes, selected at build time:
 *   -DTEAPOT_PRECALC_FRAMES=<n>  render n rotation steps into RAM once, then
 *                                play them back at the display rate
 *   -DTEAPOT_SINGLE_BUFFER=1     never flip; draw straight into the buffer
 *                                being scanned out (tearing accepted)
 *   default                      render live into the hidden buffer and flip
 * ========================================================================= */

/* Precalc mode: render this many rotation steps into RAM once, then play
 * them back at the full display rate (a flip is just a VDMA address
 * retarget). 0 renders live every frame instead. Frames are full screens,
 * ~2.8 MB each at 720p, and must all fit below 4 GiB for the VDMA. */
#ifndef TEAPOT_PRECALC_FRAMES
#define TEAPOT_PRECALC_FRAMES 0
#endif

/* A/B experiment: 1 = never flip - render straight into the scanned-out
 * buffer. Isolates the park-pointer switch from the rendering traffic. */
#ifndef TEAPOT_SINGLE_BUFFER
#define TEAPOT_SINGLE_BUFFER 0
#endif

static uintptr_t s_vdma;
static XDpPsu *s_dp;

static unsigned long s_frames;
static unsigned long s_lockdrops;
static unsigned long s_underflows;
static unsigned long s_tprev;
static int s_dead_reports;

/* Called once per displayed frame: accumulate pipeline health and, every 256
 * frames, report it (lock drops / underflows = memory contention; clean = any
 * remaining artifact is past the PL). */
static void anim_tick(void) {
    const uint32_t flags = video_status[0];
    if (!(flags & VIDSTAT_LOCKED)) s_lockdrops++;
    if (flags & VIDSTAT_UNDERFLOW) s_underflows++;
    if (++s_frames % 256 != 0) return;

    const unsigned long tnow = cycles_now();
    const unsigned long us = cycles_to_us(tnow - s_tprev);
    printf("anim: %lu frames, %lu.%lu fps, lock drops %lu, underflows %lu\n",
           s_frames, 256000000ul / us, (2560000000ul / us) % 10,
           s_lockdrops, s_underflows);
    s_lockdrops = s_underflows = 0;

    /* Sink-side truth + phase-unbiased lock waveform each report; if the sink
     * stops receiving, escalate: stream re-enable first, a full retrain one
     * report later. Their outcomes tell whether the stream, the link, or
     * neither is recoverable in software. */
    const int rx = dp_sink_status("anim");
    probe_lock("anim", 50);
    if (rx == 1) s_dead_reports = 0;
    else s_dead_reports++;
    if (s_dead_reports == 2) {
        printf("[recover] sink lost the stream - re-enabling the DP stream...\n");
        dp_start_stream(s_dp);
        dp_sink_status("after-restream");
    } else if (s_dead_reports == 4) {
        printf("[recover] still dead - full link retrain...\n");
        dp_start_link(s_dp);
        dp_start_stream(s_dp);
        dp_sink_status("after-retrain");
    }

    /* Every 1024 frames: stop flipping/copying for 2 s. Lock/stream returning
     * during the pause = memory-contention starvation; staying dead = a
     * one-shot kill (e.g. the DP dropping the live stream on a single timing
     * glitch). */
    if (s_frames % 1024 == 0) {
        printf("[idle-probe] no flips for 2 s...\n");
        probe_lock("idle", 2000);
        dp_sink_status("after-idle");
    }
    s_tprev = tnow;
}

/* Black out the display buffers (they still hold the test pattern) and take
 * the first measurements on a quiet, black screen. `flip` = 0 keeps drawing
 * into the buffer being scanned out, so that any remaining lock loss cannot
 * be blamed on the park-pointer switch. */
static void anim_blank_and_baseline(int flip) {
    if (!flip) {
        fb_draw_into(fb_store(fb_front()));
        fb_clear();
        fb_flush_draw();
        probe_lock("first-draw", 100);
        dp_sink_status("first-draw");
        return;
    }
    fb_draw_into(fb_store(fb_front() ^ 1u));
    fb_clear();
    fb_flush_draw();
    fb_flip(s_vdma);
    fb_clear(); /* the other buffer is the draw target after the flip */
    fb_flush_draw();
    probe_lock("first-flip", 100);
    dp_sink_status("first-flip");
}

#if TEAPOT_PRECALC_FRAMES > 0

/* Render every rotation step once into RAM while the test pattern is still on
 * screen, then play the frames back at the display rate. */
static void anim_precalc(void) {
    printf("Precalculating %d frames (%u MB)...\n", TEAPOT_PRECALC_FRAMES,
           (unsigned) (((uint64_t) TEAPOT_PRECALC_FRAMES * fb_size()) >> 20));
    static uint8_t *pframes[TEAPOT_PRECALC_FRAMES];
    for (int i = 0; i < TEAPOT_PRECALC_FRAMES; i++) {
        uint8_t *buf = malloc(fb_size() + 64);
        if (!buf) {
            printf("FATAL: allocation of frame %d failed - lower "
                   "TEAPOT_PRECALC_FRAMES\n", i);
            abort();
        }
        pframes[i] = (uint8_t *) (((uintptr_t) buf + 63) & ~(uintptr_t) 63);
        if (!vdma_reachable(pframes[i], fb_size())) {
            printf("FATAL: frame %d at %p crosses 4 GiB (the VDMA reads 32-bit "
                   "addresses) - lower TEAPOT_PRECALC_FRAMES\n",
                   i, (void *) pframes[i]);
            abort();
        }
        fb_draw_into(pframes[i]);
        fb_clear();
        teapot_render((float) (6.28318530718 * i / TEAPOT_PRECALC_FRAMES));
        if ((i & 31) == 31) printf("  %d/%d\n", i + 1, TEAPOT_PRECALC_FRAMES);
    }
    fb_fence();
    printf("Playback at display rate.\n");

    anim_blank_and_baseline(1);

    /* Playback = copy each prerendered frame into the hidden buffer and flip.
     * The frames only differ inside the teapot box (the background is black
     * everywhere), so only those rows are copied - ~250 KB instead of a full
     * frame, which a cache-miss-bound copy on this core cannot move at the
     * display rate. Word-wise (8-byte) copy: the nano libc's memcpy is
     * byte-wise and ~20x slower; the row offsets are 8-byte aligned. */
    unsigned bx, by, bw, bh;
    teapot_bounds(&bx, &by, &bw, &bh);
    const size_t pb_x0 = (size_t) bx * 3;
    const size_t pb_stride = (size_t) fb_width * 3;
    for (int i = 0;; i = (i + 1) % TEAPOT_PRECALC_FRAMES) {
        for (unsigned y = 0; y < bh; y++) {
            const size_t off = ((size_t) by + y) * pb_stride + pb_x0;
            const uint64_t *src = (const uint64_t *) (pframes[i] + off);
            uint64_t *dst = (uint64_t *) (fb_draw + off);
            for (size_t w = 0; w < (size_t) bw * 3 / 8; w += 4) {
                dst[w] = src[w];
                dst[w + 1] = src[w + 1];
                dst[w + 2] = src[w + 2];
                dst[w + 3] = src[w + 3];
            }
        }
        /* Only the copied rows are dirty - flushing the whole 2.76 MB frame
         * here would cost ~11x more Flush64 writes than the copy itself. */
        fb_flush_rect(bx, by, bw, bh);
        fb_flip(s_vdma);
        anim_tick();
    }
}

#else /* live rendering */

static void anim_live(void) {
    anim_blank_and_baseline(!TEAPOT_SINGLE_BUFFER);

    /* Both buffers were blanked and flushed whole above, and the renderer only
     * ever touches its box, so from here on the box is the only dirty region
     * of either buffer. */
    unsigned bx, by, bw, bh;
    teapot_bounds(&bx, &by, &bw, &bh);

    float angle = 0.0f;
    unsigned long tframe = cycles_now();
    for (;;) {
        teapot_render(angle);
        /* Push the freshly drawn box to DRAM before the DMA can fetch it
         * (a plain fence when the frame fetch is coherent). */
        fb_flush_rect(bx, by, bw, bh);
        if (!TEAPOT_SINGLE_BUFFER) fb_flip(s_vdma);

        /* Time-based rotation: constant angular speed at any frame rate. */
        const unsigned long tnow = cycles_now();
        angle += 0.7e-6f * (float) cycles_to_us(tnow - tframe);
        tframe = tnow;
        if (angle > 6.2831853f) angle -= 6.2831853f;

        anim_tick();
    }
}

#endif /* TEAPOT_PRECALC_FRAMES */

/* Runs forever. */
static void run_animation(uintptr_t vdma_base, XDpPsu *dp) {
    s_vdma = vdma_base;
    s_dp = dp;
    s_tprev = cycles_now();
#if TEAPOT_PRECALC_FRAMES > 0
    anim_precalc();
#else
    anim_live();
#endif
}

/* =========================================================================
 * Bring-up
 * ========================================================================= */

int main(void) {
    printf("=== DisplayPort bring-up test ===\n");

    /* ---- discover the pipeline ---------------------------------------- */
    open_ps_window();

    /* The VDMA node carries the mainline binding's compatible (the same one the
     * Linux dmaengine driver matches), not an IP-version-specific soct alias. */
    dtb_node *vdma_node = dt_require_compatible("xlnx,axi-vdma-1.00.a");
    uintptr_t vdma_base;
    dt_require_reg(vdma_node, &vdma_base, NULL);
    /* Decides whether rendered frames must be pushed to DRAM by hand. */
    fb_coherence_init(vdma_node);

    dtb_node *vtc_node = dt_require_compatible("xlnx,v-tc-6.2");
    uintptr_t vtc_base;
    dt_require_reg(vtc_node, &vtc_base, NULL);
    printf("VDMA at 0x%lx, VTC at 0x%lx\n", (unsigned long) vdma_base,
           (unsigned long) vtc_base);

    uintptr_t vidstat_base;
    dt_require_reg(dt_require_compatible("soct,video-status"), &vidstat_base, NULL);

    /* The busy-wait delays count core cycles; the core clock is the cbus clock. */
    dtb_node *cbus = dtb_find("/soc/cbus_clock");
    if (!cbus) {
        printf("FATAL: /soc/cbus_clock not found in the device tree\n");
        abort();
    }
    SoctXil_SetCpuFreqHz(dt_require_u32(cbus, "clock-frequency"));

    /* ---- video mode ----------------------------------------------------
     * The mode is baked into the design (pixel clock) and advertised by the
     * vtc0 node. There is no fallback: a design whose mode is not in the
     * timing table is rejected. */
    const unsigned width = (unsigned) dt_require_u32(vtc_node, "soct,hactive");
    const unsigned height = (unsigned) dt_require_u32(vtc_node, "soct,vactive");
    const unsigned fps = (unsigned) dt_require_u32(vtc_node, "soct,fps");
    XVidC_VideoMode mode = XVIDC_VM_NUM_SUPPORTED;
    for (int m = 0; m < XVIDC_VM_NUM_SUPPORTED; m++) {
        const XVidC_VideoTimingMode *e = &XVidC_VideoTimingModes[m];
        if (e->Timing.HActive == width && e->Timing.VActive == height &&
            (unsigned) e->FrameRate == fps) {
            mode = e->VmId;
            break;
        }
    }
    if (mode == XVIDC_VM_NUM_SUPPORTED) {
        printf("FATAL: the design's video mode %ux%u@%u is not in the timing table\n",
               width, height, fps);
        abort();
    }
    const XVidC_VideoTiming *timing = &XVidC_VideoTimingModes[mode].Timing;
    printf("Video mode: %s (%u x %u)\n", XVidC_VideoTimingModes[mode].Name,
           timing->HActive, timing->VActive);

    /* ---- PL side: framebuffers -> VDMA -> stream/timing ----------------- */
    fb_init(width, height);
    teapot_init();

    pattern_draw();
    memcpy(fb_store(1), fb_store(0), fb_size()); /* both buffers start with the pattern */
    fb_flush(fb_store(0), fb_size());
    fb_flush(fb_store(1), fb_size());
    vdma_start(vdma_base, fb_store(0), fb_store(1));
    vtc_start(vtc_base, timing);

    /* ---- PS side: route the live input through DP and light the link ---- */
    dp_avbuf_select_live_video();

    static XDpPsu dp;
    dp_open(&dp, mode);
    dp_start_link(&dp);
    dp_report_edid(&dp);
    dp_start_stream(&dp);
    printf("Video stream enabled - the monitor should show color bars.\n");

    /* ---- checks, then the animation ------------------------------------ */
    probe_init(&dp, (volatile uint32_t *) vidstat_base);
    verify_pipeline(vdma_base, vtc_base);
    printf("=== dp-test checks done - test pattern for 5 s, then the teapot on "
           "black (forever) ===\n");
    usleep(8000000);

    /* Baseline for the instrumentation: the stream is (supposedly) healthy here. */
    dp_sink_status("baseline");
    probe_lock("baseline", 100);

    run_experiment(vdma_base);

    run_animation(vdma_base, &dp);
    return 0;
}
