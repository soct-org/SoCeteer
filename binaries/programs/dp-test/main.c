#include <stdint.h>
#include <inttypes.h>
#include <math.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "soct/smoldtb.h"

#include "xdppsu.h"
#include "xavbuf.h"
#include "xil_io.h"
#include "xstatus.h"
#include "sleep.h"

/* =========================================================================
 * DisplayPort bring-up test
 *
 * Drives the full video pipeline of a design built with soct.WithVideoStream:
 * framebuffer (DRAM) -> AXI VDMA -> AXI4-Stream video out (+ VTC timing)
 * -> PS DP live video input -> DP main link -> monitor.
 *
 * The PS DisplayPort controller is programmed through the vendored Xilinx
 * dppsu/avbuf drivers (see xilinx/), whose PS register addresses are
 * translated through the design's address window (dpwin0 device-tree node).
 *
 * Prerequisite: the PS must have been initialized once after power-up
 * (clocks, DP SERDES/PS-GTR lanes) by running the psu_init that Vivado
 * generates alongside the design, e.g. in xsdb:
 *     targets -set -filter {name =~ "PSU"}
 *     source .../soctvivado_project.gen/.../psu_init.tcl ; psu_init
 * Without it the DP PHY never reports ready.
 * ========================================================================= */

/* The video mode is baked into the design (pixel clock) and advertised by the
 * vtc0 device-tree node (soct,hactive/vactive/fps); resolved at startup in
 * main - there is no fallback, a design without the properties is rejected. */
static XVidC_VideoMode s_mode;
static unsigned s_width;
static unsigned s_height;

/* PS-fixed base of the DP controller (AVBuf registers included) - reached
 * through the dpwin0 window, see xil_io.h. */
#define PS_DP_BASEADDR 0xFD4A0000u

/* Byte order of a framebuffer pixel. The VDMA stream carries UG934-ordered
 * RGB (tdata[7:0]=G, [15:8]=B, [23:16]=R, little-endian in memory), which the
 * design pads into the PS live-video pixel. If colors come out permuted on
 * the monitor, only these three indices need to change. */
#define FB_BYTE_G 0
#define FB_BYTE_B 1
#define FB_BYTE_R 2

/* Two heap-allocated framebuffers (too large for the program-image budget as
 * BSS); the VDMA reads them, so they must end up below 4 GiB - checked at
 * startup. The VDMA parks on the front buffer while all drawing goes to the
 * back one (s_fb); flip_buffers() swaps them at a frame boundary, so the
 * monitor never sees a half-drawn frame. */
#define FB_SIZE (s_width * s_height * 3)
static uint8_t *s_fbs[2];
static unsigned s_front_idx; /* buffer the VDMA is scanning out */
static uint8_t *s_fb;        /* draw target */

/* =========================================================================
 * Device discovery (DTB)
 * ========================================================================= */

static dtb_node *find_compatible(const char *compat) {
    dtb_node *node = dtb_find_compatible(NULL, compat);
    if (!node) {
        printf("FATAL: no device-tree node with compatible \"%s\" - was the "
               "design built with --with-config soct.WithVideoStream?\n", compat);
        abort();
    }
    return node;
}

static void read_reg(dtb_node *node, uintptr_t *base, uintptr_t *size) {
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

static unsigned long read_u32_prop(dtb_node *node, const char *name) {
    dtb_prop *prop = dtb_find_prop(node, name);
    uintmax_t val = 0;
    if (!prop || dtb_read_prop_1(prop, 1, &val) < 1) {
        printf("FATAL: could not read device-tree property \"%s\"\n", name);
        abort();
    }
    return (unsigned long) val;
}

/* =========================================================================
 * PS window probe
 *
 * A broken path to the PS registers does not fail loudly on its own: a dead
 * AXI route typically reads as all-ones, which makes every "poll until bits
 * set" check in the Xilinx driver pass vacuously (PHY "ready", monitor
 * "connected", link "trained") - or as all-zeros, which hangs the PHY poll.
 * A write/readback round-trip proves the registers actually respond.
 * ========================================================================= */

static void probe_ps_window(void) {
    /* HTOTAL is safe to scribble on: XDpPsu_SetMsaValues rewrites it before
     * the stream is enabled. */
    const u32 probe = 0xABCu;
    printf("Probing DP registers through the window... (a hang right here means "
        "the transaction stalls: the PS is not initialized - run psu_init "
        "via xsdb, then retry)\n");
    u32 initial = XDpPsu_ReadReg(PS_DP_BASEADDR, XDPPSU_MAIN_STREAM_HTOTAL);
    XDpPsu_WriteReg(PS_DP_BASEADDR, XDPPSU_MAIN_STREAM_HTOTAL, probe);
    u32 got = XDpPsu_ReadReg(PS_DP_BASEADDR, XDPPSU_MAIN_STREAM_HTOTAL);
    XDpPsu_WriteReg(PS_DP_BASEADDR, XDPPSU_MAIN_STREAM_HTOTAL, initial);
    if (got != probe) {
        printf("FATAL: DP registers do not respond through the PS window "
               "(wrote 0x%03" PRIx32 ", read back 0x%08" PRIx32 ", initial read "
               "0x%08" PRIx32 ").\n", probe, got, initial);
        if (got == 0xFFFFFFFFu) {
            printf("       All-ones reads mean the path to the PS is dead: either "
                "the SAXIGP6 -> FPD routing does not work on this silicon, or "
                "the PS/FPD is not initialized - run psu_init via xsdb first.\n");
        }
        abort();
    }
    printf("PS window probe OK: DP registers respond through the window.\n");
}

/* =========================================================================
 * Test pattern
 * ========================================================================= */

static void put_pixel(unsigned x, unsigned y, uint8_t r, uint8_t g, uint8_t b) {
    uint8_t *px = &s_fb[(y * s_width + x) * 3];
    px[FB_BYTE_R] = r;
    px[FB_BYTE_G] = g;
    px[FB_BYTE_B] = b;
}

/* Background color at (x, y): color bars over the top 3/4, a grayscale ramp
 * below, and a white frame border. */
static void background_color(unsigned x, unsigned y, uint8_t *r, uint8_t *g, uint8_t *b) {
    static const uint8_t bars[8][3] = {
        {255, 255, 255}, {255, 255, 0}, {0, 255, 255}, {0, 255, 0},
        {255, 0, 255}, {255, 0, 0}, {0, 0, 255}, {0, 0, 0},
    };
    if (x < 4 || x >= s_width - 4 || y < 4 || y >= s_height - 4) {
        *r = *g = *b = 255; /* border: clipped or shifted geometry shows immediately */
    } else if (y < (s_height * 3) / 4) {
        const uint8_t *c = bars[(x * 8) / s_width];
        *r = c[0];
        *g = c[1];
        *b = c[2];
    } else {
        uint8_t v = (uint8_t) ((x * 255) / (s_width - 1)); /* checks bit order/gamma */
        *r = *g = *b = v;
    }
}

static void draw_test_pattern(void) {
    for (unsigned y = 0; y < s_height; y++) {
        for (unsigned x = 0; x < s_width; x++) {
            uint8_t r, g, b;
            background_color(x, y, &r, &g, &b);
            put_pixel(x, y, r, g, b);
        }
    }
}



static void clear_test_pattern(void) {
    memset(s_fb, 0, FB_SIZE);
}

/* =========================================================================
 * Spinning Utah teapot
 *
 * A port of the teapot3d demo from Tsoding's olive.c (MIT): the public-domain
 * Newell teapot geometry (teapot_mesh.h), rotating around Y under a slight
 * tilt, perspective-projected and rasterized with barycentric RGB corner
 * colors and a z-buffer. All per-pixel work is incremental integer math
 * (edge functions plus 24.8 fixed-point z/color planes); floats only touch
 * the 3644 vertex transforms once per frame.
 * ========================================================================= */

#include "teapot_mesh.h"

#define TEAPOT_BOX 288 /* screen-space bounding box (pixels, square) */

/* Precalc mode: render this many rotation steps into RAM once, then play
 * them back at the full display rate (a flip is just a VDMA address
 * retarget). 0 renders live every frame instead. Configurable at build time:
 * cmake -DTEAPOT_PRECALC_FRAMES=<n> (frames are full screens, ~2.8 MB each
 * at 720p; they must all fit below 4 GiB for the VDMA). */
#ifndef TEAPOT_PRECALC_FRAMES
#define TEAPOT_PRECALC_FRAMES 256
#endif

static uint16_t *s_zbuf; /* TEAPOT_BOX x TEAPOT_BOX depth buffer */
static int16_t (*s_txy)[2]; /* per-vertex projected box coordinates */
static uint16_t *s_tz; /* per-vertex quantized depth */

static void render_teapot(float angle) {
    const int box = TEAPOT_BOX, half = box / 2;
    const int cx = (int) s_width / 2, cy = (int) s_height / 2;
    const int x0scr = cx - half, y0scr = cy - half;
    const float cs = cosf(angle), sn = sinf(angle);
    const float tc = cosf(0.45f), ts = sinf(0.45f); /* fixed viewing tilt */

    /* Transform and project every vertex into box coordinates. */
    for (int i = 0; i < TEAPOT_NVERTS; i++) {
        const float x = (float) teapot_verts[i][0] * (1.0f / 16383.0f);
        const float y = (float) teapot_verts[i][1] * (1.0f / 16383.0f);
        const float z = (float) teapot_verts[i][2] * (1.0f / 16383.0f);
        const float rx = x * cs + z * sn;
        float rz = -x * sn + z * cs;
        const float ry = y * tc - rz * ts;
        rz = y * ts + rz * tc + 2.5f; /* camera at origin, teapot 2.5 away */
        const float inv = 1.0f / rz;
        s_txy[i][0] = (int16_t) (half + 200.0f * rx * inv);
        s_txy[i][1] = (int16_t) (half - 200.0f * ry * inv);
        s_tz[i] = (uint16_t) ((rz - 1.4f) * (65535.0f / 2.2f));
    }

    /* Fresh depth (all far) and a black box (the animation runs on a black
     * screen, so a plain row memset is all the background needs). */
    memset(s_zbuf, 0xFF, (size_t) box * box * sizeof(uint16_t));
    for (int y = 0; y < box; y++) {
        memset(&s_fb[(((size_t) (y0scr + y)) * s_width + (unsigned) x0scr) * 3],
               0, (size_t) box * 3);
    }

    /* Rasterize; no culling - the z-buffer resolves visibility. */
    for (int f = 0; f < TEAPOT_NFACES; f++) {
        int ia = teapot_faces[f][0], ib = teapot_faces[f][1], ic = teapot_faces[f][2];
        /* Flip to a consistent winding so the edge functions are positive inside. */
        int ax = s_txy[ia][0], ay = s_txy[ia][1];
        int bx = s_txy[ib][0], by = s_txy[ib][1];
        int cx2 = s_txy[ic][0], cy2 = s_txy[ic][1];
        int area = (bx - ax) * (cy2 - ay) - (cx2 - ax) * (by - ay);
        /* No backface culling: the mesh winding is not consistent enough for
         * a screen-space facing test (culling visibly broke the model), so
         * the z-buffer alone resolves visibility. */
        if (area < 0) {
            const int t = ib;
            ib = ic;
            ic = t;
            const int tx = bx;
            bx = cx2;
            cx2 = tx;
            const int ty = by;
            by = cy2;
            cy2 = ty;
            area = -area;
        }
        /* Sub-4px slivers: invisible, and their steep fixed-point slopes
        * would overflow. Neighbors cover their pixels. */
        if (area < 4) continue;
        const int za = s_tz[ia], zb = s_tz[ib], zc = s_tz[ic];

        int minx = ax < bx ? ax : bx;
        if (cx2 < minx) minx = cx2;
        if (minx < 0) minx = 0;
        int miny = ay < by ? ay : by;
        if (cy2 < miny) miny = cy2;
        if (miny < 0) miny = 0;
        int maxx = ax > bx ? ax : bx;
        if (cx2 > maxx) maxx = cx2;
        if (maxx > box - 1) maxx = box - 1;
        int maxy = ay > by ? ay : by;
        if (cy2 > maxy) maxy = cy2;
        if (maxy > box - 1) maxy = box - 1;
        if (minx > maxx || miny > maxy) continue;

        /* Edge functions at the bbox origin plus their per-step deltas. */
        const int a12 = by - cy2, b12 = cx2 - bx;
        const int a20 = cy2 - ay, b20 = ax - cx2;
        const int a01 = ay - by, b01 = bx - ax;
        int32_t w0row = (int32_t) (bx - minx) * (cy2 - miny) - (int32_t) (cx2 - minx) * (by - miny);
        int32_t w1row = (int32_t) (cx2 - minx) * (ay - miny) - (int32_t) (ax - minx) * (cy2 - miny);
        int32_t w2row = (int32_t) (ax - minx) * (by - miny) - (int32_t) (bx - minx) * (ay - miny);

        /* z and RGB are linear across the triangle: 24.8 fixed-point planes,
         * one division per plane per triangle, only adds per pixel. Corner
         * colors red/green/blue, like the olive.c demo (w0->R, w1->G, w2->B). */
        const int32_t dzdx = (int32_t) (((int64_t) (zb - za) * a20 + (int64_t) (zc - za) * a01) * 256 / area);
        const int32_t dzdy = (int32_t) (((int64_t) (zb - za) * b20 + (int64_t) (zc - za) * b01) * 256 / area);
        int32_t zrow = za * 256 + (int32_t) (((int64_t) (zb - za) * w1row + (int64_t) (zc - za) * w2row) * 256 / area);
        /* The corner colors make R+G+B a constant (3*24 + 231 = 303), so blue
         * needs neither slopes nor interpolation: b = 303 - r - g. */
        const int32_t crange = 231 * 256; /* 24..255 across each corner's weight */
        const int32_t drdx = (int32_t) ((int64_t) crange * a12 / area);
        const int32_t drdy = (int32_t) ((int64_t) crange * b12 / area);
        const int32_t dgdx = (int32_t) ((int64_t) crange * a20 / area);
        const int32_t dgdy = (int32_t) ((int64_t) crange * b20 / area);
        int32_t rrow = 24 * 256 + (int32_t) ((int64_t) crange * w0row / area);
        int32_t grow = 24 * 256 + (int32_t) ((int64_t) crange * w1row / area);

        for (int y = miny; y <= maxy; y++) {
            int32_t w0 = w0row, w1 = w1row, w2 = w2row;
            int32_t zfp = zrow, rfp = rrow, gfp = grow;
            /* Walk the row with pointers; no per-pixel index math. */
            uint16_t *zp = &s_zbuf[y * box + minx];
            uint8_t *px = &s_fb[(((size_t) (y0scr + y)) * s_width
                                 + (unsigned) (x0scr + minx)) * 3];
            for (int x = minx; x <= maxx; x++) {
                if ((w0 | w1 | w2) >= 0) {
                    const uint32_t zq = (uint32_t) zfp >> 8;
                    if (zq < *zp) {
                        *zp = (uint16_t) zq;
                        const uint8_t r = (uint8_t) (rfp >> 8);
                        const uint8_t g = (uint8_t) (gfp >> 8);
                        px[FB_BYTE_R] = r;
                        px[FB_BYTE_G] = g;
                        px[FB_BYTE_B] = (uint8_t) (303 - r - g);
                    }
                }
                w0 += a12;
                w1 += a20;
                w2 += a01;
                zfp += dzdx;
                rfp += drdx;
                gfp += dgdx;
                zp++;
                px += 3;
            }
            w0row += b12;
            w1row += b20;
            w2row += b01;
            zrow += dzdy;
            rrow += drdy;
            grow += dgdy;
        }
    }
}

/* =========================================================================
 * Video Timing Controller (v_tc) generator programming
 *
 * Direct register programming following XVtc_SetGenerator (OriginMode 1) of
 * the Xilinx vtc driver; offsets from xvtc_hw.h.
 * ========================================================================= */

#define VTC_CTL         0x000u
#define VTC_ISR         0x004u /* status: frame-sync/vblank bits set while generating (W1C) */
#define VTC_GASIZE      0x060u
#define VTC_GFENC       0x068u
#define VTC_GPOL        0x06Cu
#define VTC_GHSIZE      0x070u
#define VTC_GVSIZE      0x074u
#define VTC_GHSYNC      0x078u
#define VTC_GVBHOFF     0x07Cu
#define VTC_GVSYNC      0x080u
#define VTC_GVSHOFF     0x084u
#define VTC_GVBHOFF_F1  0x088u
#define VTC_GVSYNC_F1   0x08Cu
#define VTC_GVSHOFF_F1  0x090u
#define VTC_GASIZE_F1   0x094u

#define VTC_CTL_ALLSS   0x03FDEF00u /* take every timing parameter from the generator registers */
#define VTC_CTL_GE      0x00000004u /* generator enable */
#define VTC_CTL_RU      0x00000002u /* register update */
#define VTC_CTL_SW      0x00000001u /* core enable */

/* GPOL: field id / active chroma / active video / hblank / vblank always
 * active-high; hsync / vsync polarity per video mode. */
#define VTC_POL_BASE    0x73u
#define VTC_POL_HSP     0x08u
#define VTC_POL_VSP     0x04u

#define VTC_ISR_G_VBLANK 0x1000u /* generator entered vertical blanking */

static void vtc_start(uintptr_t base, const XVidC_VideoTiming *t) {
    volatile uint32_t *vtc = (volatile uint32_t *) base;
    const uint32_t hss = (uint32_t) t->HActive + t->HFrontPorch; /* hsync start */
    const uint32_t hbs = hss + t->HSyncWidth; /* hsync end / back porch start */
    const uint32_t vss = (uint32_t) t->VActive + t->F0PVFrontPorch; /* vsync start */
    const uint32_t vbs = vss + t->F0PVSyncWidth; /* vsync end */

    vtc[VTC_GHSIZE / 4] = t->HTotal;
    vtc[VTC_GVSIZE / 4] = (uint32_t) t->F0PVTotal | ((uint32_t) t->F0PVTotal << 16);
    vtc[VTC_GASIZE / 4] = (uint32_t) t->HActive | ((uint32_t) t->VActive << 16);
    vtc[VTC_GASIZE_F1 / 4] = (uint32_t) t->VActive << 16;
    vtc[VTC_GHSYNC / 4] = hss | (hbs << 16);
    vtc[VTC_GVSYNC / 4] = vss | (vbs << 16);
    vtc[VTC_GVSYNC_F1 / 4] = vss | (vbs << 16);
    vtc[VTC_GFENC / 4] = 0; /* progressive */
    vtc[VTC_GVBHOFF / 4] = (uint32_t) t->HActive | ((uint32_t) t->HActive << 16);
    vtc[VTC_GVSHOFF / 4] = hss | (hss << 16);
    vtc[VTC_GVBHOFF_F1 / 4] = (uint32_t) t->HActive | ((uint32_t) t->HActive << 16);
    vtc[VTC_GVSHOFF_F1 / 4] = hss | (hss << 16);
    vtc[VTC_GPOL / 4] = VTC_POL_BASE | (t->HSyncPolarity ? VTC_POL_HSP : 0)
                        | (t->VSyncPolarity ? VTC_POL_VSP : 0);
    vtc[VTC_CTL / 4] = VTC_CTL_ALLSS | VTC_CTL_RU | VTC_CTL_GE | VTC_CTL_SW;
}

/* =========================================================================
 * AXI VDMA MM2S programming (register direct mode, pg020)
 * ========================================================================= */

#define VDMA_MM2S_DMACR        0x00u
#define VDMA_MM2S_DMASR        0x04u
#define VDMA_PARK_PTR          0x28u
#define VDMA_MM2S_VSIZE        0x50u
#define VDMA_MM2S_HSIZE        0x54u
#define VDMA_MM2S_FRMDLY_STRIDE 0x58u
#define VDMA_MM2S_START_ADDR1  0x5Cu

#define VDMA_DMACR_RS          0x1u
#define VDMA_DMACR_CIRCULAR    0x2u
#define VDMA_DMACR_RESET       0x4u
#define VDMA_DMASR_HALTED      0x1u
#define VDMA_DMASR_FRMCNT_IRQ  0x1000u /* set (even unmasked) when a full frame completed */
#define VDMA_DMASR_ERR_ALL     0x0FF0u

#define VDMA_NUM_FSTORES 3 /* the IP's c_num_fstores default; stores 0/1 = the two buffers */

#define VDMA_PARK_PTR_RD_MASK       0x1Fu
#define VDMA_PARK_PTR_RDSTORE_SHIFT 16 /* readback: frame store currently being scanned */

/* Park the VDMA on the given frame store and wait until it actually switches
 * (it does so at a frame boundary, which also paces the caller to the
 * display rate). */
static void flip_to_store(uintptr_t vdma_base, unsigned store) {
    volatile uint32_t *vdma = (volatile uint32_t *) vdma_base;
    const uint32_t pp = vdma[VDMA_PARK_PTR / 4];
    vdma[VDMA_PARK_PTR / 4] = (pp & ~VDMA_PARK_PTR_RD_MASK) | store;
    for (int i = 0;; i++) {
        const uint32_t cur = (vdma[VDMA_PARK_PTR / 4] >> VDMA_PARK_PTR_RDSTORE_SHIFT)
                             & VDMA_PARK_PTR_RD_MASK;
        if (cur == store) break;
        if (i > 1000) { /* >100 ms: several missed frame boundaries */
            printf("FATAL: VDMA never switched to frame store %u (PARK_PTR=0x%08" PRIx32 ")\n",
                   store, vdma[VDMA_PARK_PTR / 4]);
            abort();
        }
        usleep(100);
    }
    s_front_idx = store;
}

/* Tear-free page flip between the two drawing buffers: show the freshly drawn
 * one, then draw into the now-hidden one. The frame-store addresses stay
 * fixed for the VDMA's whole lifetime: retargeting them dynamically requires
 * a VSIZE rewrite, and that visibly glitches the frame being scanned out. */
static void flip_buffers(uintptr_t vdma_base) {
    flip_to_store(vdma_base, s_front_idx ^ 1u);
    s_fb = s_fbs[s_front_idx ^ 1u];
}

static void vdma_start(uintptr_t base, const uint8_t *fb0, const uint8_t *fb1) {
    volatile uint32_t *vdma = (volatile uint32_t *) base;

    const uint8_t *fbs[VDMA_NUM_FSTORES] = {fb0, fb1, fb0};
    for (int i = 0; i < 2; i++) {
        if ((uintptr_t) fbs[i] + FB_SIZE > 0x100000000ull) {
            printf("FATAL: framebuffer at %p crosses 4 GiB - the VDMA read master is "
                   "32-bit, frames must live in DRAM's first 2 GiB\n", (const void *) fbs[i]);
            abort();
        }
    }

    vdma[VDMA_MM2S_DMACR / 4] = VDMA_DMACR_RESET;
    while (vdma[VDMA_MM2S_DMACR / 4] & VDMA_DMACR_RESET) {
    }

    /* Read-modify-write keeps the reset defaults (e.g. IRQFrameCount = 1).
     * Park mode (no circular bit): the VDMA repeats the frame store selected
     * by PARK_PTR, which flip_buffers() retargets for tear-free page flips. */
    vdma[VDMA_MM2S_DMACR / 4] |= VDMA_DMACR_RS;
    vdma[VDMA_PARK_PTR / 4] = 0;
    for (int i = 0; i < VDMA_NUM_FSTORES; i++) {
        vdma[(VDMA_MM2S_START_ADDR1 + 4 * i) / 4] = (uint32_t) (uintptr_t) fbs[i];
    }
    vdma[VDMA_MM2S_HSIZE / 4] = s_width * 3;
    vdma[VDMA_MM2S_FRMDLY_STRIDE / 4] = s_width * 3;
    vdma[VDMA_MM2S_VSIZE / 4] = s_height; /* written last: starts the transfers */

    usleep(1000);
    uint32_t sr = vdma[VDMA_MM2S_DMASR / 4];
    if (sr & VDMA_DMASR_HALTED) {
        printf("FATAL: VDMA did not start (MM2S_DMASR=0x%08" PRIx32 ")\n", sr);
        abort();
    }
    printf("VDMA running (MM2S_DMASR=0x%08" PRIx32 ")\n", sr);
}

/* =========================================================================
 * DisplayPort link + stream (vendored dppsu/avbuf drivers)
 * ========================================================================= */

static void dp_start_link(XDpPsu *dp) {
    printf("DP PHY status before init: 0x%08" PRIx32 "\n",
           (u32) XDpPsu_ReadReg(dp->Config.BaseAddr, XDPPSU_PHY_STATUS));
    if (XDpPsu_InitializeTx(dp) != XST_SUCCESS) {
        printf("FATAL: DP TX initialization failed (PHY not ready) - did psu_init "
            "run after power-up?\n");
        abort();
    }

    printf("Waiting for a DisplayPort monitor (HPD)...\n");
    while (!XDpPsu_IsConnected(dp)) {
        usleep(100000);
    }
    printf("Monitor connected.\n");

    if (XDpPsu_GetRxCapabilities(dp) != XST_SUCCESS) {
        printf("FATAL: reading the monitor's DPCD capabilities failed (AUX channel)\n");
        abort();
    }
    printf("Sink: max link rate 0x%02x, max lane count %d\n",
           dp->LinkConfig.MaxLinkRate, dp->LinkConfig.MaxLaneCount);

    XDpPsu_SetEnhancedFrameMode(dp, 1);
    XDpPsu_SetDownspread(dp, 0);
    if (XDpPsu_CfgMainLinkMax(dp) != XST_SUCCESS) {
        printf("FATAL: configuring the main link to maximum capabilities failed\n");
        abort();
    }
    if (XDpPsu_EstablishLink(dp) != XST_SUCCESS) {
        printf("FATAL: link training failed\n");
        abort();
    }
    printf("Link trained: %d Mbps per lane, %d lane(s)\n",
           270 * dp->LinkConfig.LinkRate, dp->LinkConfig.LaneCount);
}

static void dp_start_stream(XDpPsu *dp) {
    XDpPsu_SetColorEncode(dp, XDPPSU_CENC_RGB);
    XDpPsu_CfgMsaSetBpc(dp, 8);
    XDpPsu_CfgMsaEnSynchClkMode(dp, 1);
    XDpPsu_CfgMsaUseStandardVideoMode(dp, s_mode);

    /* Idle pattern while reconfiguring, then reset the TX and push the MSA. */
    XDpPsu_EnableMainLink(dp, 0);
    XDpPsu_WriteReg(dp->Config.BaseAddr, XDPPSU_SOFT_RESET, 0x1);
    XDpPsu_WriteReg(dp->Config.BaseAddr, XDPPSU_SOFT_RESET, 0x0);
    XDpPsu_SetVideoMode(dp);
    XDpPsu_EnableMainLink(dp, 1);
}

static void avbuf_select_live_video(void) {
    static XAVBuf avbuf;
    XAVBuf_CfgInitialize(&avbuf, PS_DP_BASEADDR, 0);
    /* DISABLEGFX, not NONE: the NONE enum value (0xC0) lands outside the
     * stream-2 field and would spray bits into the audio selects. */
    XAVBuf_InputVideoSelect(&avbuf, XAVBUF_VIDSTREAM1_LIVE, XAVBUF_VIDSTREAM2_DISABLEGFX);
    XAVBuf_InputAudioSelect(&avbuf, XAVBUF_AUDSTREAM1_NO_AUDIO, XAVBUF_AUDSTREAM2_NO_AUDIO);
    if (XAVBuf_SetInputLiveVideoFormat(&avbuf, RGB_8BPC) != XST_SUCCESS) {
        printf("FATAL: AVBuf rejected the live input format\n");
        abort();
    }
    if (XAVBuf_SetOutputVideoFormat(&avbuf, RGB_8BPC) != XST_SUCCESS) {
        printf("FATAL: AVBuf rejected the output format\n");
        abort();
    }
    XAVBuf_ConfigureVideoPipeline(&avbuf);
    XAVBuf_ConfigureOutputVideo(&avbuf);
    XAVBuf_SetBlenderAlpha(&avbuf, 0, 0); /* video stream only, no graphics blending */
    /* Diagnostic background: the blender shows it wherever no video arrives.
     * RED on the monitor = DP output path works, live video input does not. */
    XAVBuf_BlenderBgClr bg = {.RCr = 0xFFF, .GY = 0, .BCb = 0};
    XAVBuf_BlendSetBgColor(&avbuf, &bg);
    printf("Blender background set to RED: a red screen means the DP output works "
        "but the live video is not reaching it.\n");
    /* Live input: the pixel clock comes from the PL (the driver enforces this
     * for live sources and soft-resets the pipeline). */
    XAVBuf_SetAudioVideoClkSrc(&avbuf, XAVBUF_PL_CLK, XAVBUF_PS_CLK);
}

/* =========================================================================
 * Pipeline verification: prove each clock/data domain is alive after enable
 * ========================================================================= */

static void verify_pipeline(uintptr_t vdma_base, uintptr_t vtc_base) {
    volatile uint32_t *vdma = (volatile uint32_t *) vdma_base;
    volatile uint32_t *vtc = (volatile uint32_t *) vtc_base;

    /* Video pipeline status GPIO (vidstat0 node): {bit2 overflow, bit1
     * underflow, bit0 locked} at +0x0. The locked bit is the definitive
     * "video is leaving the PL" signal; underflow means the stream starves. */
    dtb_node *vs = find_compatible("soct,video-status");
    uintptr_t vs_base;
    read_reg(vs, &vs_base, NULL);
    volatile uint32_t *gpio = (volatile uint32_t *) vs_base;
    const uint32_t flags = gpio[0];
    printf("Video-out status: locked=%u underflow=%u overflow=%u\n",
           flags & 1u, (flags >> 1) & 1u, (flags >> 2) & 1u);
    if (!(flags & 1u)) {
        printf("  BAD: the video out is NOT locked - no video leaves the PL.\n");
    }

    /* Clear the sticky status bits, then watch for a few frame times. */
    vtc[VTC_ISR / 4] = 0xFFFFFFFFu;
    vdma[VDMA_MM2S_DMASR / 4] = VDMA_DMASR_FRMCNT_IRQ;
    usleep(100000); /* 100 ms = ~6 frames at 60 Hz */
    const uint32_t isr = vtc[VTC_ISR / 4];
    const uint32_t sr = vdma[VDMA_MM2S_DMASR / 4];
    printf("Pipeline check after 100 ms: VTC ISR=0x%08" PRIx32 ", VDMA MM2S_DMASR=0x%08" PRIx32 "\n",
           isr, sr);

    if (sr & VDMA_DMASR_ERR_ALL) {
        printf("  BAD: VDMA reports errors (internal/slave/decode/size) - frame reads "
            "from DRAM are failing.\n");
    }
    if (isr & VTC_ISR_G_VBLANK) {
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
        if (vdma[VDMA_MM2S_DMASR / 4] & VDMA_DMASR_FRMCNT_IRQ) {
            vdma[VDMA_MM2S_DMASR / 4] = VDMA_DMASR_FRMCNT_IRQ;
            frames++;
        }
    }
    printf("VDMA frame delivery rate: %u frames/s (60 needed for real-time)\n", frames);

    /* Lock behavior over time: never locked = alignment problem; toggling =
     * lock gained then lost to FIFO starvation. */
    if (vs) {
        uintptr_t vs_base;
        read_reg(vs, &vs_base, NULL);
        volatile uint32_t *gpio = (volatile uint32_t *) vs_base;
        printf("Lock/underflow samples over 2 s (L=locked U=underflow): ");
        for (int i = 0; i < 20; i++) {
            const uint32_t f = gpio[0];
            printf("%c%c ", (f & 1u) ? 'L' : '-', (f & 2u) ? 'U' : '-');
            usleep(100000);
        }
        printf("\n");
    }
}

/* ========================================================================= */

int main(void) {
    printf("=== DisplayPort bring-up test ===\n");

    /* Discover the pipeline from the device tree. */
    dtb_node *win = find_compatible("soct,zynqmp-dp-window");
    uintptr_t win_base, win_size;
    read_reg(win, &win_base, &win_size);
    uintptr_t ps_base = (uintptr_t) read_u32_prop(win, "soct,ps-base");
    SoctXil_SetPsWindow(ps_base, win_base, win_size);
    printf("PS window: 0x%lx..0x%lx -> PS 0x%lx\n", (unsigned long) win_base,
           (unsigned long) (win_base + win_size - 1), (unsigned long) ps_base);
    probe_ps_window();

    uintptr_t vdma_base, vtc_base;
    read_reg(find_compatible("xlnx,axi-vdma-6.3"), &vdma_base, NULL);
    dtb_node *vtc_node = find_compatible("xlnx,v-tc-6.2");
    read_reg(vtc_node, &vtc_base, NULL);
    printf("VDMA at 0x%lx, VTC at 0x%lx\n", (unsigned long) vdma_base,
           (unsigned long) vtc_base);

    /* The busy-wait delays count core cycles; the core clock is the cbus clock. */
    dtb_node *cbus = dtb_find("/soc/cbus_clock");
    if (!cbus) {
        printf("FATAL: /soc/cbus_clock not found in the device tree\n");
        abort();
    }
    SoctXil_SetCpuFreqHz(read_u32_prop(cbus, "clock-frequency"));

    /* The design advertises its video mode on the vtc node (read_u32_prop
     * fails loudly when a property is missing). */
    s_width = (unsigned) read_u32_prop(vtc_node, "soct,hactive");
    s_height = (unsigned) read_u32_prop(vtc_node, "soct,vactive");
    const unsigned fps = (unsigned) read_u32_prop(vtc_node, "soct,fps");
    int mode_found = 0;
    for (int m = 0; m < XVIDC_VM_NUM_SUPPORTED; m++) {
        const XVidC_VideoTimingMode *e = &XVidC_VideoTimingModes[m];
        if (e->Timing.HActive == s_width && e->Timing.VActive == s_height &&
            (unsigned) e->FrameRate == fps) {
            s_mode = e->VmId;
            mode_found = 1;
            break;
        }
    }
    if (!mode_found) {
        printf("FATAL: the design's video mode %ux%u@%u is not in the timing table\n",
               s_width, s_height, fps);
        abort();
    }

    /* PL side: framebuffer -> VDMA -> stream/timing towards the PS live input. */
    const XVidC_VideoTiming *timing = &XVidC_VideoTimingModes[s_mode].Timing;
    printf("Video mode: %s (%u x %u)\n", XVidC_VideoTimingModes[s_mode].Name,
           timing->HActive, timing->VActive);
    for (int i = 0; i < 2; i++) {
        uint8_t *fb = malloc(FB_SIZE + 64);
        if (!fb) {
            printf("FATAL: framebuffer allocation (%u bytes) failed\n", FB_SIZE + 64);
            abort();
        }
        s_fbs[i] = (uint8_t *) (((uintptr_t) fb + 63) & ~(uintptr_t) 63);
    }
    printf("Framebuffers at %p / %p\n", (void *) s_fbs[0], (void *) s_fbs[1]);
    s_front_idx = 0;
    s_fb = s_fbs[0]; /* checks draw on the scanned-out buffer; animation flips */

    s_zbuf = malloc((size_t) TEAPOT_BOX * TEAPOT_BOX * sizeof(uint16_t));
    s_txy = malloc(TEAPOT_NVERTS * sizeof(*s_txy));
    s_tz = malloc(TEAPOT_NVERTS * sizeof(*s_tz));
    if (!s_zbuf || !s_txy || !s_tz) {
        printf("FATAL: teapot buffer allocation failed\n");
        abort();
    }
    draw_test_pattern();
    memcpy(s_fbs[1], s_fbs[0], FB_SIZE); /* both buffers start with the pattern */
    __asm__ volatile("fence" ::: "memory"); /* framebuffers visible before DMA starts */
    vdma_start(vdma_base, s_fbs[0], s_fbs[1]);
    vtc_start(vtc_base, timing);

    /* PS side: route the live input through the DP controller and light the link. */
    avbuf_select_live_video();

    static XDpPsu dp;
    XDpPsu_Config cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.BaseAddr = PS_DP_BASEADDR;
    XDpPsu_CfgInitialize(&dp, &cfg, cfg.BaseAddr);

    dp_start_link(&dp);

    u8 edid[XDPPSU_EDID_BLOCK_SIZE];
    if (XDpPsu_GetEdid(&dp, edid) == XST_SUCCESS) {
        printf("EDID: manufacturer bytes %02x %02x, product %02x%02x\n",
               edid[8], edid[9], edid[11], edid[10]);
    } else {
        printf("note: EDID read failed (non-fatal, continuing)\n");
    }

    dp_start_stream(&dp);

    printf("Video stream enabled - the monitor should show color bars.\n");
    verify_pipeline(vdma_base, vtc_base);
    printf("=== dp-test checks done - test pattern for 5 s, then the teapot on "
           "black (forever) ===\n");
    usleep(5000000);

    /* The pipeline health monitor of the animation loop needs the status GPIO. */
    uintptr_t vidstat_base;
    read_reg(find_compatible("soct,video-status"), &vidstat_base, NULL);
    volatile uint32_t *vidstat = (volatile uint32_t *) vidstat_base;

    unsigned long frames = 0, lockdrops = 0, underflows = 0;
    unsigned long tprev;
    __asm__ volatile("csrr %0, mcycle" : "=r"(tprev));

#if TEAPOT_PRECALC_FRAMES > 0
    /* Precalc mode: render every rotation step once into RAM while the test
     * pattern stays on screen, then play the frames back at the display rate
     * (each flip is only a VDMA address retarget). */
    printf("Precalculating %d frames (%u MB)...\n", TEAPOT_PRECALC_FRAMES,
           (unsigned) (((uint64_t) TEAPOT_PRECALC_FRAMES * FB_SIZE) >> 20));
    static uint8_t *pframes[TEAPOT_PRECALC_FRAMES];
    for (int i = 0; i < TEAPOT_PRECALC_FRAMES; i++) {
        uint8_t *buf = malloc(FB_SIZE + 64);
        if (!buf) {
            printf("FATAL: allocation of frame %d failed - lower "
                   "TEAPOT_PRECALC_FRAMES\n", i);
            abort();
        }
        pframes[i] = (uint8_t *) (((uintptr_t) buf + 63) & ~(uintptr_t) 63);
        if ((uintptr_t) pframes[i] + FB_SIZE > 0x100000000ull) {
            printf("FATAL: frame %d at %p crosses 4 GiB (the VDMA reads 32-bit "
                   "addresses) - lower TEAPOT_PRECALC_FRAMES\n",
                   i, (void *) pframes[i]);
            abort();
        }
        s_fb = pframes[i];
        clear_test_pattern();
        render_teapot((float) (6.28318530718 * i / TEAPOT_PRECALC_FRAMES));
        if ((i & 31) == 31) printf("  %d/%d\n", i + 1, TEAPOT_PRECALC_FRAMES);
    }
    __asm__ volatile("fence" ::: "memory");
    printf("Playback at display rate.\n");

    /* Blank both display buffers (they still hold the test pattern). */
    s_fb = s_fbs[s_front_idx ^ 1u];
    clear_test_pattern();
    __asm__ volatile("fence" ::: "memory");
    flip_buffers(vdma_base);
    clear_test_pattern();
    __asm__ volatile("fence" ::: "memory");

    /* Playback = copy each prerendered frame into the hidden buffer and flip.
     * The frames only differ inside the teapot box (the background is black
     * everywhere), so only those rows are copied - ~250 KB instead of a full
     * frame, which a cache-miss-bound copy on this core cannot move at the
     * display rate. Word-wise (8-byte) copy: the nano libc's memcpy is
     * byte-wise and ~20x slower; the row offsets are 8-byte aligned. */
    const int pb_box = TEAPOT_BOX;
    const size_t pb_x0 = ((size_t) s_width / 2 - pb_box / 2) * 3;
    const size_t pb_y0 = (size_t) s_height / 2 - pb_box / 2;
    const size_t pb_stride = (size_t) s_width * 3;
    for (int i = 0;; i = (i + 1) % TEAPOT_PRECALC_FRAMES) {
        for (int y = 0; y < pb_box; y++) {
            const size_t off = (pb_y0 + (size_t) y) * pb_stride + pb_x0;
            const uint64_t *src = (const uint64_t *) (pframes[i] + off);
            uint64_t *dst = (uint64_t *) (s_fb + off);
            for (size_t w = 0; w < (size_t) pb_box * 3 / 8; w += 4) {
                dst[w] = src[w];
                dst[w + 1] = src[w + 1];
                dst[w + 2] = src[w + 2];
                dst[w + 3] = src[w + 3];
            }
        }
        __asm__ volatile("fence" ::: "memory");
        flip_buffers(vdma_base);
#else
    /* Live mode: black out both buffers, then draw into the hidden buffer and
     * flip at the next frame boundary (flip_buffers also paces us). */
    s_fb = s_fbs[1];
    clear_test_pattern();
    __asm__ volatile("fence" ::: "memory");
    flip_buffers(vdma_base);
    clear_test_pattern(); /* s_fb is the other buffer after the flip */

    float teapot_angle = 0.0f;
    unsigned long tframe = tprev;
    for (;;) {
        render_teapot(teapot_angle);
        __asm__ volatile("fence" ::: "memory");
        flip_buffers(vdma_base);
        /* Time-based rotation: constant angular speed at any frame rate. */
        unsigned long tnowf;
        __asm__ volatile("csrr %0, mcycle" : "=r"(tnowf));
        teapot_angle += 0.7e-6f * (float) ((tnowf - tframe) / SoctXil_GetCyclesPerUs());
        tframe = tnowf;
        if (teapot_angle > 6.2831853f) teapot_angle -= 6.2831853f;
#endif

        /* Pipeline health while animating (lock drops / underflows = memory
         * contention; clean = any remaining artifact is past the PL). */
        const uint32_t vflags = vidstat[0];
        if (!(vflags & 1u)) lockdrops++;
        if (vflags & 2u) underflows++;
        if (++frames % 256 == 0) {
            unsigned long tnow;
            __asm__ volatile("csrr %0, mcycle" : "=r"(tnow));
            const unsigned long us = (tnow - tprev) / SoctXil_GetCyclesPerUs();
            printf("anim: %lu frames, %lu.%lu fps, lock drops %lu, underflows %lu\n",
                   frames, 256000000ul / us, (2560000000ul / us) % 10,
                   lockdrops, underflows);
            lockdrops = underflows = 0;
            tprev = tnow;
        }
    }
}
