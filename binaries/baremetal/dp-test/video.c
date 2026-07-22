#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sleep.h"
#include "video.h"

/* =========================================================================
 * Framebuffers
 * ========================================================================= */

unsigned fb_width;
unsigned fb_height;
uint8_t *fb_draw;

static uint8_t *s_stores[2];
static unsigned s_front; /* store the VDMA is scanning out */

void fb_init(unsigned width, unsigned height) {
    fb_width = width;
    fb_height = height;
    for (int i = 0; i < 2; i++) {
        uint8_t *buf = malloc(fb_size() + 64);
        if (!buf) {
            printf("FATAL: framebuffer allocation (%zu bytes) failed\n", fb_size() + 64);
            abort();
        }
        s_stores[i] = (uint8_t *) (((uintptr_t) buf + 63) & ~(uintptr_t) 63);
    }
    printf("Framebuffers at %p / %p\n", (void *) s_stores[0], (void *) s_stores[1]);
    s_front = 0;
    fb_draw = s_stores[0]; /* checks draw on the scanned-out buffer; the animation flips */
}

uint8_t *fb_store(unsigned i) {
    return s_stores[i & 1u];
}

unsigned fb_front(void) {
    return s_front;
}

void fb_draw_into(uint8_t *buf) {
    fb_draw = buf;
}

void fb_clear(void) {
    memset(fb_draw, 0, fb_size());
}

void fb_flip(uintptr_t vdma_base) {
    const unsigned next = s_front ^ 1u;
    vdma_park_wait(vdma_base, next);
    s_front = next;
    fb_draw = s_stores[next ^ 1u];
}

/* =========================================================================
 * Framebuffer visibility (incoherent frame fetch)
 *
 * With soct.WithIncoherentVideoStream the VDMA masters the memory controller
 * directly, so DRAM is not coherent with the CPU's caches for the frames: the
 * rendered pixels sit dirty in the write-back L1 (and in the L2, if any) and
 * the DMA would fetch stale memory. The device tree marks such a pipeline
 * `soct,incoherent`; the flush below writes those lines out before the DMA
 * reads them. Two mechanisms, picked from what the design actually has:
 *
 *   - an L2 (`sifive,inclusivecache0`): write each line's physical address to
 *     the Flush64 control register. The write blocks until the line is out,
 *     and because the cache is inclusive this also pulls the line from L1.
 *   - no L2: the L1 is small (16 KiB), so reading a buffer of that size
 *     evicts every framebuffer line - and without an L2 an eviction goes
 *     straight to DRAM. Crude, but it is the only lever the base ISA leaves
 *     us (this Rocket has no Zicbom cache-block instructions).
 * ========================================================================= */

#define L2_FLUSH64_OFFSET 0x200u /* InclusiveCache control: flush one physical address */
#define L1_DCACHE_BYTES   (16u << 10)

static int s_incoherent;                /* device tree says the frame fetch bypasses coherence */
static volatile uint64_t *s_l2_flush;   /* Flush64 register, NULL when the design has no L2 */
static volatile uint64_t *s_evict_buf;  /* L1-eviction scratch, used only without an L2 */

void fb_coherence_init(dtb_node *vdma_node) {
    s_incoherent = dtb_find_prop(vdma_node, "soct,incoherent") != NULL;
    if (!s_incoherent) {
        printf("Frame fetch is coherent - no framebuffer flushing needed.\n");
        return;
    }
    dtb_node *l2 = dtb_find_compatible(NULL, "sifive,inclusivecache0");
    if (l2) {
        uintptr_t l2_base;
        dt_require_reg(l2, &l2_base, NULL);
        s_l2_flush = (volatile uint64_t *) (l2_base + L2_FLUSH64_OFFSET);
        printf("Frame fetch is INCOHERENT - flushing frames via the L2 Flush64 register at %p.\n",
               (void *) s_l2_flush);
    } else {
        s_evict_buf = malloc(L1_DCACHE_BYTES);
        if (!s_evict_buf) {
            printf("FATAL: could not allocate the L1 eviction buffer\n");
            abort();
        }
        printf("Frame fetch is INCOHERENT and the design has no L2 - flushing frames by "
               "evicting the L1.\n");
    }
}

/* Write back every cache line overlapping [addr, addr+len). Cost is
 * proportional to the range, which is why flushing only the dirty region pays
 * off (see fb_flush_rect). No fences - the callers below bracket them. */
static void l2_flush_range(const void *addr, size_t len) {
    const uintptr_t start = (uintptr_t) addr & ~(uintptr_t) 63;
    const uintptr_t end = ((uintptr_t) addr + len + 63) & ~(uintptr_t) 63;
    for (uintptr_t a = start; a < end; a += 64) {
        *s_l2_flush = (uint64_t) a; /* blocking: returns once the line is written back */
    }
}

/* Read a cache-sized buffer: every set gets refilled, so the framebuffer lines
 * are evicted (and, with no L2 behind us, land in DRAM). Address-independent,
 * so one sweep covers any region - never call it per row. */
static void l1_evict_all(void) {
    volatile uint64_t sink = 0;
    for (size_t i = 0; i < L1_DCACHE_BYTES / sizeof(uint64_t); i += 8) {
        sink += s_evict_buf[i];
    }
    (void) sink;
}

void fb_flush(const void *addr, size_t len) {
    fb_fence(); /* stores complete before we push them out (or before the DMA reads them) */
    if (!s_incoherent) return;
    if (s_l2_flush) l2_flush_range(addr, len);
    else l1_evict_all();
    fb_fence();
}

void fb_flush_draw(void) {
    fb_flush(fb_draw, fb_size());
}

void fb_flush_rect(unsigned x, unsigned y, unsigned w, unsigned h) {
    fb_fence();
    if (!s_incoherent) return;
    if (s_l2_flush) {
        const size_t stride = (size_t) fb_width * 3;
        for (unsigned r = 0; r < h; r++) {
            l2_flush_range(fb_draw + ((size_t) (y + r)) * stride + (size_t) x * 3,
                           (size_t) w * 3);
        }
    } else {
        l1_evict_all(); /* the sweep is not addressed; the region does not matter */
    }
    fb_fence();
}

/* =========================================================================
 * AXI VDMA MM2S programming (register direct mode, pg020)
 * ========================================================================= */

#define VDMA_MM2S_DMACR         0x00u
#define VDMA_MM2S_DMASR         0x04u
#define VDMA_PARK_PTR           0x28u
#define VDMA_MM2S_VSIZE         0x50u
#define VDMA_MM2S_HSIZE         0x54u
#define VDMA_MM2S_FRMDLY_STRIDE 0x58u
#define VDMA_MM2S_START_ADDR1   0x5Cu

#define VDMA_DMACR_RS       0x1u
#define VDMA_DMACR_CIRCULAR 0x2u
#define VDMA_DMACR_RESET    0x4u

#define VDMA_PARK_PTR_RD_MASK       0x1Fu
#define VDMA_PARK_PTR_RDSTORE_SHIFT 16 /* readback: frame store currently being scanned */

int vdma_reachable(const void *buf, size_t len) {
    return (uintptr_t) buf + len <= 0x100000000ull;
}

uint32_t vdma_status(uintptr_t base) {
    volatile uint32_t *vdma = (volatile uint32_t *) base;
    return vdma[VDMA_MM2S_DMASR / 4];
}

void vdma_clear_frame_done(uintptr_t base) {
    volatile uint32_t *vdma = (volatile uint32_t *) base;
    vdma[VDMA_MM2S_DMASR / 4] = VDMA_DMASR_FRMCNT_IRQ;
}

int vdma_take_frame_done(uintptr_t base) {
    volatile uint32_t *vdma = (volatile uint32_t *) base;
    if (!(vdma[VDMA_MM2S_DMASR / 4] & VDMA_DMASR_FRMCNT_IRQ)) return 0;
    vdma[VDMA_MM2S_DMASR / 4] = VDMA_DMASR_FRMCNT_IRQ;
    return 1;
}

void vdma_park(uintptr_t base, unsigned store) {
    volatile uint32_t *vdma = (volatile uint32_t *) base;
    const uint32_t pp = vdma[VDMA_PARK_PTR / 4];
    vdma[VDMA_PARK_PTR / 4] = (pp & ~VDMA_PARK_PTR_RD_MASK) | store;
}

void vdma_park_wait(uintptr_t base, unsigned store) {
    volatile uint32_t *vdma = (volatile uint32_t *) base;
    vdma_park(base, store);
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
}

void vdma_start(uintptr_t base, const uint8_t *fb0, const uint8_t *fb1) {
    volatile uint32_t *vdma = (volatile uint32_t *) base;

    /* Store 2 repeats store 0: the IP always has three, and an unprogrammed
     * one would fetch from address 0 if park mode ever slipped. */
    const uint8_t *fbs[VDMA_NUM_FSTORES] = {fb0, fb1, fb0};
    for (int i = 0; i < 2; i++) {
        if (!vdma_reachable(fbs[i], fb_size())) {
            printf("FATAL: framebuffer at %p crosses 4 GiB - the VDMA read master is "
                   "32-bit, frames must live in DRAM's first 2 GiB\n", (const void *) fbs[i]);
            abort();
        }
    }

    vdma[VDMA_MM2S_DMACR / 4] = VDMA_DMACR_RESET;
    while (vdma[VDMA_MM2S_DMACR / 4] & VDMA_DMACR_RESET) {
    }

    /* Read-modify-write keeps the reset defaults (e.g. IRQFrameCount = 1) -
     * EXCEPT the Circular_Park bit, whose reset value is 1 (circular): it must
     * be cleared explicitly or the VDMA cycles ALL frame stores and PARK_PTR
     * is ignored - the "hidden" buffer is then scanned out every third frame
     * (seen on hardware as teapot/test-pattern flicker). Park mode: the VDMA
     * repeats the store selected by PARK_PTR, which fb_flip() retargets for
     * tear-free page flips. */
    vdma[VDMA_MM2S_DMACR / 4] =
        (vdma[VDMA_MM2S_DMACR / 4] | VDMA_DMACR_RS) & ~VDMA_DMACR_CIRCULAR;
    vdma[VDMA_PARK_PTR / 4] = 0;
    for (int i = 0; i < VDMA_NUM_FSTORES; i++) {
        vdma[(VDMA_MM2S_START_ADDR1 + 4 * i) / 4] = (uint32_t) (uintptr_t) fbs[i];
    }
    vdma[VDMA_MM2S_HSIZE / 4] = fb_width * 3;
    vdma[VDMA_MM2S_FRMDLY_STRIDE / 4] = fb_width * 3;
    vdma[VDMA_MM2S_VSIZE / 4] = fb_height; /* written last: starts the transfers */

    usleep(1000);
    uint32_t sr = vdma[VDMA_MM2S_DMASR / 4];
    if (sr & VDMA_DMASR_HALTED) {
        printf("FATAL: VDMA did not start (MM2S_DMASR=0x%08" PRIx32 ")\n", sr);
        abort();
    }
    printf("VDMA running (MM2S_DMASR=0x%08" PRIx32 ")\n", sr);
}

/* =========================================================================
 * Video Timing Controller (v_tc) generator programming
 *
 * Direct register programming following XVtc_SetGenerator (OriginMode 1) of
 * the Xilinx vtc driver; offsets from xvtc_hw.h.
 * ========================================================================= */

#define VTC_CTL        0x000u
#define VTC_ISR        0x004u /* status: frame-sync/vblank bits set while generating (W1C) */
#define VTC_GASIZE     0x060u
#define VTC_GFENC      0x068u
#define VTC_GPOL       0x06Cu
#define VTC_GHSIZE     0x070u
#define VTC_GVSIZE     0x074u
#define VTC_GHSYNC     0x078u
#define VTC_GVBHOFF    0x07Cu
#define VTC_GVSYNC     0x080u
#define VTC_GVSHOFF    0x084u
#define VTC_GVBHOFF_F1 0x088u
#define VTC_GVSYNC_F1  0x08Cu
#define VTC_GVSHOFF_F1 0x090u
#define VTC_GASIZE_F1  0x094u

#define VTC_CTL_ALLSS 0x03FDEF00u /* take every timing parameter from the generator registers */
#define VTC_CTL_GE    0x00000004u /* generator enable */
#define VTC_CTL_RU    0x00000002u /* register update */
#define VTC_CTL_SW    0x00000001u /* core enable */

/* GPOL: field id / active chroma / active video / hblank / vblank always
 * active-high; hsync / vsync polarity per video mode. */
#define VTC_POL_BASE 0x73u
#define VTC_POL_HSP  0x08u
#define VTC_POL_VSP  0x04u

#define VTC_ISR_G_VBLANK 0x1000u /* generator entered vertical blanking */

void vtc_start(uintptr_t base, const XVidC_VideoTiming *t) {
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

void vtc_clear_status(uintptr_t base) {
    volatile uint32_t *vtc = (volatile uint32_t *) base;
    vtc[VTC_ISR / 4] = 0xFFFFFFFFu;
}

uint32_t vtc_status(uintptr_t base) {
    volatile uint32_t *vtc = (volatile uint32_t *) base;
    return vtc[VTC_ISR / 4];
}

int vtc_saw_vblank(uintptr_t base) {
    return (vtc_status(base) & VTC_ISR_G_VBLANK) != 0;
}
