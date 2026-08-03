/*
 * soctfb implementation: the bring-up sequence proven by dp-test, packaged
 * behind one init call, plus small software drawing primitives. The heavy
 * lifting stays in the modules next door (video.c: framebuffers, VDMA, VTC;
 * dp.c: PS window, AVBuf, DP link) - this file orchestrates and draws.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "dp.h"
#include "soctdt.h"
#include "soctfb.h"
#include "soctfb_font.h"
#include "video.h"

/* ---------------------------------------------------------------------------
 * Fail-loud device-tree accessors (declared in soctdt.h). Every lookup names
 * a piece of the pipeline the design either has or does not have, so a miss
 * is a build/configuration error: abort with the missing name.
 * ------------------------------------------------------------------------- */

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

/* ---------------------------------------------------------------------------
 * Bring-up
 * ------------------------------------------------------------------------- */

static uintptr_t s_vdma_base;
static XDpPsu s_dp;

/* Map the window through which the vendored Xilinx sources reach the PS
 * registers (see xil_io.h), then prove the path actually responds. */
static void open_ps_window(void) {
    dtb_node *win = dt_require_compatible("soct,zynqmp-ps-window");
    uintptr_t win_base, win_size;
    dt_require_reg(win, &win_base, &win_size);
    const uintptr_t ps_base = (uintptr_t) dt_require_u32(win, "soct,ps-base");
    SoctXil_SetPsWindow(ps_base, win_base, win_size);
    dp_probe_ps_window();
}

void soctfb_init(soctfb *fb) {
    open_ps_window();

    /* The VDMA node carries the mainline binding's compatible (the same one
     * the Linux dmaengine driver matches). */
    dtb_node *vdma_node = dt_require_compatible("xlnx,axi-vdma-1.00.a");
    dt_require_reg(vdma_node, &s_vdma_base, NULL);
    fb_coherence_init(vdma_node);

    dtb_node *vtc_node = dt_require_compatible("xlnx,v-tc-6.2");
    uintptr_t vtc_base;
    dt_require_reg(vtc_node, &vtc_base, NULL);

    /* The busy-wait delays count core cycles; the core clock is the cbus clock. */
    dtb_node *cbus = dtb_find("/soc/cbus_clock");
    if (!cbus) {
        printf("FATAL: /soc/cbus_clock not found in the device tree\n");
        abort();
    }
    SoctXil_SetCpuFreqHz(dt_require_u32(cbus, "clock-frequency"));

    /* The mode is baked into the design (pixel clock) and advertised by the
     * vtc0 node; a design whose mode is not in the timing table is rejected. */
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

    /* PL side: black frames -> VDMA -> stream/timing. */
    fb_init(width, height);
    fb_draw_into(fb_store(0));
    fb_clear();
    memcpy(fb_store(1), fb_store(0), fb_size());
    fb_flush(fb_store(0), fb_size());
    fb_flush(fb_store(1), fb_size());
    vdma_start(s_vdma_base, fb_store(0), fb_store(1));
    vtc_start(vtc_base, timing);

    /* PS side: route the live input through DP and light the link. */
    dp_avbuf_select_live_video();
    dp_open(&s_dp, mode);
    dp_start_link(&s_dp);
    dp_start_stream(&s_dp);

    fb->width = width;
    fb->height = height;
    fb->pixels = fb_draw;
    printf("soctfb: %ux%u@%u up, drawing to the scanned-out frame\n", width, height, fps);
}

/* ---------------------------------------------------------------------------
 * Drawing
 * ------------------------------------------------------------------------- */

static inline uint8_t *px_at(soctfb *fb, unsigned x, unsigned y) {
    return fb->pixels + ((size_t) y * fb->width + x) * 3;
}

static inline void px_set(uint8_t *p, soctfb_rgb rgb) {
    p[0] = (uint8_t) rgb;         /* B */
    p[1] = (uint8_t) (rgb >> 8);  /* G */
    p[2] = (uint8_t) (rgb >> 16); /* R */
}

/* Clamp a box to the frame; returns 0 when nothing remains. */
static int clip(const soctfb *fb, unsigned *x, unsigned *y, unsigned *w, unsigned *h) {
    if (*x >= fb->width || *y >= fb->height) return 0;
    if (*x + *w > fb->width) *w = fb->width - *x;
    if (*y + *h > fb->height) *h = fb->height - *y;
    return *w && *h;
}

void soctfb_pixel(soctfb *fb, unsigned x, unsigned y, soctfb_rgb rgb) {
    if (x < fb->width && y < fb->height) px_set(px_at(fb, x, y), rgb);
}

void soctfb_fill(soctfb *fb, unsigned x, unsigned y, unsigned w, unsigned h, soctfb_rgb rgb) {
    if (!clip(fb, &x, &y, &w, &h)) return;
    for (unsigned r = 0; r < h; r++) {
        uint8_t *p = px_at(fb, x, y + r);
        for (unsigned c = 0; c < w; c++, p += 3) px_set(p, rgb);
    }
}

void soctfb_rect(soctfb *fb, unsigned x, unsigned y, unsigned w, unsigned h,
                 unsigned thickness, soctfb_rgb rgb) {
    if (thickness * 2 >= w || thickness * 2 >= h) {
        soctfb_fill(fb, x, y, w, h, rgb);
        return;
    }
    soctfb_fill(fb, x, y, w, thickness, rgb);                          /* top */
    soctfb_fill(fb, x, y + h - thickness, w, thickness, rgb);          /* bottom */
    soctfb_fill(fb, x, y + thickness, thickness, h - 2 * thickness, rgb); /* left */
    soctfb_fill(fb, x + w - thickness, y + thickness, thickness, h - 2 * thickness, rgb);
}

unsigned soctfb_text(soctfb *fb, unsigned x, unsigned y, const char *s, soctfb_rgb rgb) {
    for (; *s; s++, x += SOCTFB_GLYPH_W) {
        const uint8_t *glyph = soctfb_glyph(*s);
        for (unsigned r = 0; r < SOCTFB_GLYPH_H; r++) {
            const uint8_t bits = glyph[r / 2]; /* 8x8 face, each row doubled */
            for (unsigned c = 0; c < SOCTFB_GLYPH_W; c++) {
                if (bits & (0x80u >> c)) soctfb_pixel(fb, x + c, y + r, rgb);
            }
        }
    }
    return x;
}

void soctfb_blit_gray(soctfb *fb, unsigned x, unsigned y, unsigned w, unsigned h,
                      const uint8_t *gray) {
    const unsigned src_w = w;
    if (!clip(fb, &x, &y, &w, &h)) return;
    for (unsigned r = 0; r < h; r++) {
        const uint8_t *src = gray + (size_t) r * src_w;
        uint8_t *p = px_at(fb, x, y + r);
        for (unsigned c = 0; c < w; c++, p += 3) {
            p[0] = p[1] = p[2] = src[c];
        }
    }
}

void soctfb_blit_gray_scaled(soctfb *fb, unsigned x, unsigned y, unsigned w, unsigned h,
                             const uint8_t *gray, unsigned scale) {
    unsigned ow = w * scale, oh = h * scale;
    if (!scale || !clip(fb, &x, &y, &ow, &oh)) return;
    for (unsigned r = 0; r < oh; r++) {
        const uint8_t *src = gray + (size_t) (r / scale) * w;
        uint8_t *p = px_at(fb, x, y + r);
        for (unsigned c = 0; c < ow; c++, p += 3) {
            p[0] = p[1] = p[2] = src[c / scale];
        }
    }
}

/* ---------------------------------------------------------------------------
 * Visibility and double buffering (video.c does the real work)
 * ------------------------------------------------------------------------- */

void soctfb_flush(soctfb *fb, unsigned x, unsigned y, unsigned w, unsigned h) {
    if (!clip(fb, &x, &y, &w, &h)) return;
    fb_draw_into(fb->pixels); /* fb_flush_rect works on the fb_draw target */
    fb_flush_rect(x, y, w, h);
}

void soctfb_flush_all(soctfb *fb) {
    fb_flush(fb->pixels, fb_size());
}

void soctfb_backbuffer(soctfb *fb) {
    fb_draw_into(fb_store(fb_front() ^ 1u));
    fb->pixels = fb_draw;
}

void soctfb_flip(soctfb *fb) {
    fb_flip(s_vdma_base);
    fb->pixels = fb_draw;
}
