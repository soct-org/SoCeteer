/*
 * What gets drawn into the framebuffer: the startup test pattern and the
 * spinning teapot.
 */
#ifndef DP_TEST_RENDER_H
#define DP_TEST_RENDER_H

/* Color bars over the top 3/4, a grayscale ramp below, and a white frame
 * border. Between them they expose the failure modes a still image can show
 * at all - permuted color channels, wrong bit order or gamma, and clipped or
 * shifted geometry. */
void pattern_draw(void);

#define TEAPOT_BOX 288 /* screen-space bounding box (pixels, square) */

/* Allocate the z-buffer and vertex scratch. Call once, after fb_init(). */
void teapot_init(void);

/* Clear the box to black and draw the teapot rotated by `angle` radians. */
void teapot_render(float angle);

/* The rectangle teapot_render() writes to - the only part of the frame it
 * dirties, and so the only part that has to be flushed or copied. Lives here
 * so it cannot drift away from the renderer's own placement. */
void teapot_bounds(unsigned *x, unsigned *y, unsigned *w, unsigned *h);

#endif /* DP_TEST_RENDER_H */
