#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "render.h"
#include "video.h"

/* =========================================================================
 * Test pattern
 * ========================================================================= */

static void background_color(unsigned x, unsigned y, uint8_t *r, uint8_t *g, uint8_t *b) {
    static const uint8_t bars[8][3] = {
        {255, 255, 255}, {255, 255, 0}, {0, 255, 255}, {0, 255, 0},
        {255, 0, 255}, {255, 0, 0}, {0, 0, 255}, {0, 0, 0},
    };
    if (x < 4 || x >= fb_width - 4 || y < 4 || y >= fb_height - 4) {
        *r = *g = *b = 255; /* border: clipped or shifted geometry shows immediately */
    } else if (y < (fb_height * 3) / 4) {
        const uint8_t *c = bars[(x * 8) / fb_width];
        *r = c[0];
        *g = c[1];
        *b = c[2];
    } else {
        uint8_t v = (uint8_t) ((x * 255) / (fb_width - 1)); /* checks bit order/gamma */
        *r = *g = *b = v;
    }
}

void pattern_draw(void) {
    for (unsigned y = 0; y < fb_height; y++) {
        for (unsigned x = 0; x < fb_width; x++) {
            uint8_t r, g, b;
            background_color(x, y, &r, &g, &b);
            fb_put_pixel(x, y, r, g, b);
        }
    }
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
 *
 * The model is drawn centered into a TEAPOT_BOX-sized square of the current
 * draw buffer; nothing outside that box is touched, which is what keeps the
 * animation affordable on this core.
 * ========================================================================= */

#include "teapot_mesh.h"

static uint16_t *s_zbuf;    /* TEAPOT_BOX x TEAPOT_BOX depth buffer */
static int16_t (*s_txy)[2]; /* per-vertex projected box coordinates */
static uint16_t *s_tz;      /* per-vertex quantized depth */

void teapot_init(void) {
    s_zbuf = malloc((size_t) TEAPOT_BOX * TEAPOT_BOX * sizeof(uint16_t));
    s_txy = malloc(TEAPOT_NVERTS * sizeof(*s_txy));
    s_tz = malloc(TEAPOT_NVERTS * sizeof(*s_tz));
    if (!s_zbuf || !s_txy || !s_tz) {
        printf("FATAL: teapot buffer allocation failed\n");
        abort();
    }
}

void teapot_bounds(unsigned *x, unsigned *y, unsigned *w, unsigned *h) {
    *x = fb_width / 2 - TEAPOT_BOX / 2;
    *y = fb_height / 2 - TEAPOT_BOX / 2;
    *w = TEAPOT_BOX;
    *h = TEAPOT_BOX;
}

void teapot_render(float angle) {
    /* Hoisted once: the framebuffer globals do not move during a frame, and
     * the inner loops must not reload them. */
    uint8_t *const fb = fb_draw;
    const unsigned width = fb_width;

    const int box = TEAPOT_BOX, half = box / 2;
    const int cx = (int) width / 2, cy = (int) fb_height / 2;
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
        memset(&fb[(((size_t) (y0scr + y)) * width + (unsigned) x0scr) * 3],
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
            uint8_t *px = &fb[(((size_t) (y0scr + y)) * width
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
