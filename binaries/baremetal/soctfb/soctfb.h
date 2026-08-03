/*
 * soctfb - the bare-metal framebuffer: one call brings the whole DisplayPort
 * pipeline up (device-tree discovery, VDMA, timing generator, DP link), then
 * drawing is pixels and a flush.
 *
 * Works on coherent and incoherent designs alike: soctfb_flush() pushes the
 * given region out through the L2's Flush64 register where the frame fetch
 * bypasses the caches, and is a plain fence where it does not - callers flush
 * what they drew and never think about the difference.
 *
 * Single-buffered by default: drawing goes straight to the scanned-out frame.
 * For tear-free animation draw with soctfb_backbuffer() and present with
 * soctfb_flip().
 *
 * Prerequisite: the PS must have been initialized once after power-up
 * (psu_init via xsdb) - the flash prelude does it automatically. See
 * binaries/baremetal/fb-template for the minimal program.
 */
#ifndef SOCTFB_H
#define SOCTFB_H

#include <stddef.h>
#include <stdint.h>

typedef struct {
    unsigned width, height; /* the design's fixed video mode */
    uint8_t *pixels;        /* current draw target, 24bpp: B,G,R per pixel */
} soctfb;

/* Brings the pipeline up and leaves the (black) frame on screen. Aborts with
 * the missing piece named when the design has no video pipeline. */
void soctfb_init(soctfb *fb);

/* An 0xRRGGBB color literal, e.g. 0xFF0000 for red. */
typedef uint32_t soctfb_rgb;

void soctfb_pixel(soctfb *fb, unsigned x, unsigned y, soctfb_rgb rgb);
void soctfb_fill(soctfb *fb, unsigned x, unsigned y, unsigned w, unsigned h, soctfb_rgb rgb);
/* Outline of the given line thickness, drawn INSIDE the w x h box. */
void soctfb_rect(soctfb *fb, unsigned x, unsigned y, unsigned w, unsigned h,
                 unsigned thickness, soctfb_rgb rgb);
/* 8x16 pixels per character; digits, uppercase letters (lowercase folds to
 * uppercase) and basic punctuation. Returns the x after the last glyph. */
unsigned soctfb_text(soctfb *fb, unsigned x, unsigned y, const char *s, soctfb_rgb rgb);
/* 8-bit grayscale source rows (w bytes each), expanded to 24bpp. */
void soctfb_blit_gray(soctfb *fb, unsigned x, unsigned y, unsigned w, unsigned h,
                      const uint8_t *gray);
/* Same source, drawn scale-times larger (nearest neighbor). */
void soctfb_blit_gray_scaled(soctfb *fb, unsigned x, unsigned y, unsigned w, unsigned h,
                             const uint8_t *gray, unsigned scale);

/* Make a drawn region visible to the scanout. Every draw needs a flush after
 * it (or one flush covering everything drawn); flushing costs time only on
 * incoherent designs, proportional to the region. */
void soctfb_flush(soctfb *fb, unsigned x, unsigned y, unsigned w, unsigned h);
void soctfb_flush_all(soctfb *fb);

/* Double buffering: retarget drawing to the hidden frame, then swap. flip()
 * flushes nothing - flush what was drawn first. */
void soctfb_backbuffer(soctfb *fb);
void soctfb_flip(soctfb *fb);

#endif /* SOCTFB_H */
