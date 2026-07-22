/*
 * The PL video pipeline: where frames live and the hardware that scans them
 * out. Three layers, in the order the pixels travel:
 *
 *   fb_*    framebuffers in DRAM - geometry, double buffering, and making
 *           CPU-written pixels visible to the DMA
 *   vdma_*  the AXI VDMA MM2S channel that fetches them (pg020)
 *   vtc_*   the timing generator that turns the pixel stream into video
 *
 * The split of responsibility between the first two is deliberate: fb_* owns
 * which buffer is which, vdma_* owns the registers. fb_flip() is the one place
 * they meet.
 */
#ifndef DP_TEST_VIDEO_H
#define DP_TEST_VIDEO_H

#include <stddef.h>
#include <stdint.h>

#include "dptest.h"
#include "xvidc.h"

/* ---------------------------------------------------------------------------
 * Framebuffers
 * ------------------------------------------------------------------------- */

/* Byte order of a framebuffer pixel. The VDMA stream carries UG934-ordered
 * RGB (tdata[7:0]=G, [15:8]=B, [23:16]=R, little-endian in memory), which the
 * design pads into the PS live-video pixel. If colors come out permuted on
 * the monitor, only these three indices need to change. */
#define FB_BYTE_G 0
#define FB_BYTE_B 1
#define FB_BYTE_R 2

/* Video size, from the design's vtc0 node (see main). */
extern unsigned fb_width;
extern unsigned fb_height;

/* The buffer all drawing goes to - the one the VDMA is NOT scanning out. */
extern uint8_t *fb_draw;

static inline size_t fb_size(void) {
    return (size_t) fb_width * fb_height * 3;
}

/* Allocate both buffers for a width x height frame and start drawing into
 * store 0 (which is also the one the VDMA is told to park on first). */
void fb_init(unsigned width, unsigned height);

/* Frame store `i` (0 or 1) and the store currently being scanned out. */
uint8_t *fb_store(unsigned i);
unsigned fb_front(void);

/* Retarget drawing without flipping (used by the render-into-RAM modes). */
void fb_draw_into(uint8_t *buf);

/* Black out the draw buffer. */
void fb_clear(void);

static inline void fb_put_pixel(unsigned x, unsigned y, uint8_t r, uint8_t g, uint8_t b) {
    uint8_t *px = &fb_draw[(y * fb_width + x) * 3];
    px[FB_BYTE_R] = r;
    px[FB_BYTE_G] = g;
    px[FB_BYTE_B] = b;
}

/* Show the freshly drawn buffer and start drawing into the now-hidden one.
 * Blocks until the VDMA has actually switched, which also paces the caller to
 * the display rate. */
void fb_flip(uintptr_t vdma_base);

/* Order our stores before whatever reads them next. */
static inline void fb_fence(void) {
    __asm__ volatile("fence" ::: "memory");
}

/* Decide how frames must be pushed to DRAM, from the VDMA node's
 * `soct,incoherent` property (see video.c). */
void fb_coherence_init(dtb_node *vdma_node);

/* Make `len` bytes at `addr` visible to the frame-fetch DMA. Always fences,
 * and on a coherent design that is all it does - so callers can flush
 * unconditionally. */
void fb_flush(const void *addr, size_t len);

/* fb_flush() over the whole draw buffer. */
void fb_flush_draw(void);

/* fb_flush() over a `w` x `h` pixel rectangle at (x, y) of the draw buffer.
 * A frame is 2.76 MB at 720p but an animation typically redraws a small part
 * of it; flushing only that part is what keeps the per-frame cost off the
 * frame rate (see video.c - with an L2 the work is proportional to the region,
 * so the saving is real, and the whole-cache fallback is unaffected). */
void fb_flush_rect(unsigned x, unsigned y, unsigned w, unsigned h);

/* ---------------------------------------------------------------------------
 * AXI VDMA MM2S
 * ------------------------------------------------------------------------- */

/* Frame stores the design gives us. NOT an IP default: the generator pins
 * `c_num_fstores` (AXIVideoDMA.FrameStores) and advertises the same value as
 * `xlnx,num-fstores`, chosen at three so a Linux driver can page-flip
 * tear-free. This test only double-buffers, so it uses stores 0/1 and points
 * store 2 at store 0. */
#define VDMA_NUM_FSTORES 3

#define VDMA_DMASR_HALTED     0x1u
#define VDMA_DMASR_ERR_ALL    0x0FF0u
#define VDMA_DMASR_FRMCNT_IRQ 0x1000u /* set (even unmasked) when a full frame completed */

/* Reset the engine, point the frame stores at the two buffers, park on store 0
 * and start fetching. Aborts if the engine does not leave the halted state. */
void vdma_start(uintptr_t base, const uint8_t *fb0, const uint8_t *fb1);

/* Select the frame store to scan out. The switch happens at the next frame
 * boundary; vdma_park() only writes the register, vdma_park_wait() also waits
 * for the readback to confirm it (and aborts if it never does). */
void vdma_park(uintptr_t base, unsigned store);
void vdma_park_wait(uintptr_t base, unsigned store);

uint32_t vdma_status(uintptr_t base);

/* Frame-complete flag (set even while unmasked). Reading it clears it, so
 * counting calls that return 1 measures the engine's achieved frame rate. */
int vdma_take_frame_done(uintptr_t base);
void vdma_clear_frame_done(uintptr_t base);

/* The read master is 32-bit: everything it fetches must live below 4 GiB. */
int vdma_reachable(const void *buf, size_t len);

/* ---------------------------------------------------------------------------
 * Video Timing Controller (v_tc)
 * ------------------------------------------------------------------------- */

void vtc_start(uintptr_t base, const XVidC_VideoTiming *t);

/* Sticky status bits (write-1-clear, so reads do not disturb them): clear
 * them, wait, then ask whether the generator has entered vertical blanking
 * since - i.e. whether it is generating frames at all. */
void vtc_clear_status(uintptr_t base);
uint32_t vtc_status(uintptr_t base);
int vtc_saw_vblank(uintptr_t base);

#endif /* DP_TEST_VIDEO_H */
