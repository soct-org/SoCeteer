/*
 * fb-template - the minimal bare-metal program that draws to the monitor.
 * Copy this directory, rename it, and start from here.
 *
 * soctfb_init() brings the whole DisplayPort pipeline up from the device tree;
 * after it, drawing is primitives plus a flush of what was drawn. The flush is
 * what makes the same program correct on coherent AND incoherent designs - it
 * costs nothing where the frame fetch is coherent.
 */
#include <stdio.h>

#include "soctfb.h"

int main(void) {
    soctfb fb;
    soctfb_init(&fb);

    /* Background, a few labeled boxes, a color-bar strip. */
    soctfb_fill(&fb, 0, 0, fb.width, fb.height, 0x202028);

    static const soctfb_rgb bars[] = {0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00,
                                      0xFF00FF, 0xFF0000, 0x0000FF, 0x000000};
    const unsigned bar_w = fb.width / 8;
    for (unsigned i = 0; i < 8; i++) {
        soctfb_fill(&fb, i * bar_w, fb.height - 120, bar_w, 120, bars[i]);
    }

    soctfb_text(&fb, 40, 40, "HELLO FROM SOCTFB", 0xFFFFFF);
    soctfb_rect(&fb, 40, 80, 320, 200, 4, 0x00FF00);
    soctfb_text(&fb, 52, 92, "A BOX: 0.97", 0x00FF00);
    soctfb_rect(&fb, 420, 140, 260, 180, 4, 0xFF4040);
    soctfb_text(&fb, 432, 152, "ANOTHER: 0.42", 0xFF4040);

    /* One flush for everything drawn above. */
    soctfb_flush_all(&fb);

    printf("fb-template: drawn - the monitor should show boxes and color bars\n");
    for (;;) {
    }
}
