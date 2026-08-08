// SPDX-License-Identifier: GPL-2.0
/*
 * soct-dp: brings the SoCeteer DisplayPort pipeline up and leaves it scanning the
 * console framebuffer.
 *
 * The pipeline is framebuffer (DRAM) -> AXI VDMA -> AXI4-Stream video out (+ VTC
 * timing) -> PS DP live video input -> DP main link -> monitor, and every stage is
 * discovered from the device tree - including the video timing itself: the vtc node
 * carries the complete `display-timings` structure of the synthesized mode, so any
 * mode the generator accepts drives the monitor without this module knowing it in
 * advance. The framebuffer is the fixed reserved-memory carve-out; who serves it as
 * a framebuffer device depends on how the VDMA reaches DRAM. On a coherent design
 * the /chosen/framebuffer node describes it: simplefb registers it and fbcon
 * renders into it independently of this module, which only starts the hardware
 * that puts it on the monitor. On an incoherent design (`soct,incoherent` on the
 * VDMA node) there is no /chosen node - CPU writes sit in the caches where the
 * scanout never sees them, so this module registers its own cache-flushing
 * framebuffer device instead (fb.c).
 *
 * Incoherent designs also expose the pixel-clock MMCM for runtime reconfiguration:
 * the `soct,pixel-clkwiz` node carries the retune budget - the MMCM's input clock,
 * its analog window, and the clock ceiling timing closure ran at. There is no mode
 * list, deliberately: whether a timing works is the monitor's call, which no table
 * can predict, so userspace submits any complete timing through the standard fbdev
 * interfaces (the shell image ships `fbmode` for it) and this module solves the
 * MMCM dividers against the budget, refusing only what the hardware itself cannot
 * do. The pixel domain's reset rides the MMCM's LOCKED output, so the domain
 * resets itself across a retune.
 *
 * The VDMA node is status-disabled precisely so the dmaengine driver keeps its hands
 * off (it resets every channel at probe); this module programs the engine's register
 *-direct park mode itself. The PS side is driven by the vendored Xilinx dppsu/avbuf
 * sources, compiled in with kernel shims (copied beside this file at build time - see
 * extra-sources.txt).
 *
 * Everything runs in a worker: link training sleeps, and a monitor can take seconds
 * to wake. The processing system must have been initialized (psu_init) this power-on;
 * the register-window probe fails loudly when it was not.
 */
#include <linux/delay.h>
#include <linux/io.h>
#include <linux/iopoll.h>
#include <linux/math.h>
#include <linux/module.h>
#include <linux/of.h>
#include <linux/of_address.h>
#include <linux/workqueue.h>

#include "clk-wzrd.h"
#include "dp.h"
#include "fb.h"
#include "xavbuf.h"
#include "xdppsu.h"
#include "xil_io.h"
#include "xvidc.h"

/* PS-fixed base of the DP controller (AVBuf registers included). */
#define PS_DP_BASEADDR 0xFD4A0000u

/* AXI VDMA MM2S, register direct mode (pg020) - same map as the bare-metal tests. */
#define VDMA_MM2S_DMACR         0x00u
#define VDMA_MM2S_DMASR         0x04u
#define VDMA_PARK_PTR           0x28u
#define VDMA_MM2S_VSIZE         0x50u
#define VDMA_MM2S_HSIZE         0x54u
#define VDMA_MM2S_FRMDLY_STRIDE 0x58u
#define VDMA_MM2S_START_ADDR1   0x5Cu
#define VDMA_DMACR_RS           0x1u
#define VDMA_DMACR_CIRCULAR     0x2u
#define VDMA_DMACR_RESET        0x4u
#define VDMA_DMASR_HALTED       0x1u
#define VDMA_NUM_FSTORES        3

/* Video Timing Controller generator registers (xvtc_hw.h). */
#define VTC_CTL        0x000u
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
#define VTC_CTL_ALLSS  0x03FDEF00u
#define VTC_CTL_GE     0x00000004u
#define VTC_CTL_RU     0x00000002u
#define VTC_CTL_SW     0x00000001u
#define VTC_POL_BASE   0x73u
#define VTC_POL_HSP    0x08u
#define VTC_POL_VSP    0x04u

static unsigned int hpd_timeout_ms = 30000;
module_param(hpd_timeout_ms, uint, 0644);
MODULE_PARM_DESC(hpd_timeout_ms,
		 "How long to wait for a DisplayPort monitor before giving up");

static struct {
	void __iomem *vdma;
	void __iomem *vtc;
	void __iomem *vidstat;
	void __iomem *window;
	void __iomem *pixclk; /* reconfigurable pixel MMCM; NULL on static designs */
	phys_addr_t fb;
	resource_size_t fb_size;
	struct soct_dp_mode cur;         /* what the pipeline is scanning */
	bool retunable;
	struct soct_clk_wzrd_limits lim; /* the MMCM budget, from the device tree */
	u32 max_hz;                      /* clock ceiling: timing closure ran here */
	XDpPsu dp;
	XAVBuf avbuf;
} s;

static u32 mode_htotal(const struct soct_dp_mode *m)
{
	return m->hactive + m->hfront_porch + m->hsync_len + m->hback_porch;
}

static u32 mode_vtotal(const struct soct_dp_mode *m)
{
	return m->vactive + m->vfront_porch + m->vsync_len + m->vback_porch;
}

static int mode_prop(struct device_node *np, const char *name, const char *alt, u32 *out)
{
	if (!of_property_read_u32(np, name, out))
		return 0;
	return alt ? of_property_read_u32(np, alt, out) : -EINVAL;
}

/* Parses the vtc node's timing (it spells the active area and refresh with a
 * `soct,` prefix, keeping generic bindings from misreading them). */
static int of_read_mode(struct device_node *np, struct soct_dp_mode *m)
{
	if (mode_prop(np, "hactive", "soct,hactive", &m->hactive) ||
	    mode_prop(np, "hfront-porch", NULL, &m->hfront_porch) ||
	    mode_prop(np, "hsync-len", NULL, &m->hsync_len) ||
	    mode_prop(np, "hback-porch", NULL, &m->hback_porch) ||
	    mode_prop(np, "vactive", "soct,vactive", &m->vactive) ||
	    mode_prop(np, "vfront-porch", NULL, &m->vfront_porch) ||
	    mode_prop(np, "vsync-len", NULL, &m->vsync_len) ||
	    mode_prop(np, "vback-porch", NULL, &m->vback_porch) ||
	    mode_prop(np, "hsync-active", NULL, &m->hsync_active) ||
	    mode_prop(np, "vsync-active", NULL, &m->vsync_active) ||
	    mode_prop(np, "fps", "soct,fps", &m->fps) ||
	    mode_prop(np, "clock-frequency", NULL, &m->pixel_clock_hz))
		return -EINVAL;
	return 0;
}

static int vdma_halt(void)
{
	u32 sr;

	writel(VDMA_DMACR_RESET, s.vdma + VDMA_MM2S_DMACR);
	if (read_poll_timeout(readl, sr, !(sr & VDMA_DMACR_RESET), 100, 100000,
			      false, s.vdma + VDMA_MM2S_DMACR)) {
		pr_err("soct-dp: VDMA reset did not complete\n");
		return -EIO;
	}
	return 0;
}

static int vdma_start(phys_addr_t fb, u32 width, u32 height)
{
	unsigned int i;
	int err;
	u32 sr;

	err = vdma_halt();
	if (err)
		return err;

	/* Keep the reset defaults EXCEPT Circular_Park, whose reset value is 1: cleared, the
	 * engine parks on the store PARK_PTR selects instead of cycling all of them. Store 1
	 * is the second frame when the carve-out holds two (the fbdev then offers panning,
	 * soct_dp_set_scanout_frame flips); otherwise every store carries the one frame. */
	writel((readl(s.vdma + VDMA_MM2S_DMACR) | VDMA_DMACR_RS) & ~VDMA_DMACR_CIRCULAR,
	       s.vdma + VDMA_MM2S_DMACR);
	writel(0, s.vdma + VDMA_PARK_PTR);
	for (i = 0; i < VDMA_NUM_FSTORES; i++) {
		size_t frame = (size_t)width * 3 * height;
		phys_addr_t store = (i == 1 && 2 * frame <= s.fb_size) ? fb + frame : fb;

		writel(lower_32_bits(store), s.vdma + VDMA_MM2S_START_ADDR1 + 4 * i);
	}
	writel(width * 3, s.vdma + VDMA_MM2S_HSIZE);
	writel(width * 3, s.vdma + VDMA_MM2S_FRMDLY_STRIDE);
	writel(height, s.vdma + VDMA_MM2S_VSIZE); /* written last: starts the transfers */

	usleep_range(1000, 2000);
	sr = readl(s.vdma + VDMA_MM2S_DMASR);
	if (sr & VDMA_DMASR_HALTED) {
		pr_err("soct-dp: VDMA did not start (MM2S_DMASR=0x%08x)\n", sr);
		return -EIO;
	}
	return 0;
}

/* Park the scanout on frame 0 or 1; the VDMA applies a new park pointer at the
 * next frame boundary, so the switch itself can never tear. Only meaningful
 * when vdma_start pointed store 1 at a second frame - the fbdev offers panning
 * exactly then (same carve-out arithmetic on both sides). */
void soct_dp_set_scanout_frame(unsigned int frame)
{
	writel(frame & 0x1fu, s.vdma + VDMA_PARK_PTR);
}

static void vtc_start(const struct soct_dp_mode *m)
{
	const u32 hss = m->hactive + m->hfront_porch;
	const u32 hbs = hss + m->hsync_len;
	const u32 vss = m->vactive + m->vfront_porch;
	const u32 vbs = vss + m->vsync_len;

	writel(mode_htotal(m), s.vtc + VTC_GHSIZE);
	writel(mode_vtotal(m) | (mode_vtotal(m) << 16), s.vtc + VTC_GVSIZE);
	writel(m->hactive | (m->vactive << 16), s.vtc + VTC_GASIZE);
	writel(m->vactive << 16, s.vtc + VTC_GASIZE_F1);
	writel(hss | (hbs << 16), s.vtc + VTC_GHSYNC);
	writel(vss | (vbs << 16), s.vtc + VTC_GVSYNC);
	writel(vss | (vbs << 16), s.vtc + VTC_GVSYNC_F1);
	writel(0, s.vtc + VTC_GFENC); /* progressive */
	writel(m->hactive | (m->hactive << 16), s.vtc + VTC_GVBHOFF);
	writel(hss | (hss << 16), s.vtc + VTC_GVSHOFF);
	writel(m->hactive | (m->hactive << 16), s.vtc + VTC_GVBHOFF_F1);
	writel(hss | (hss << 16), s.vtc + VTC_GVSHOFF_F1);
	writel(VTC_POL_BASE | (m->hsync_active ? VTC_POL_HSP : 0)
	       | (m->vsync_active ? VTC_POL_VSP : 0), s.vtc + VTC_GPOL);
	writel(VTC_CTL_ALLSS | VTC_CTL_RU | VTC_CTL_GE | VTC_CTL_SW, s.vtc + VTC_CTL);
}

/* Retunes the pixel MMCM (clk-wzrd.h). LOCKED drops during the retune and the
 * pixel domain resets itself (its reset rides LOCKED), so every pixel-domain core
 * must be reprogrammed afterwards; the register interfaces this module touches all
 * sit in the periphery clock domain and stay reachable throughout. */
static int pixclk_retune(const struct soct_clk_wzrd_setting *set)
{
	int err = soct_clk_wzrd_retune(s.pixclk, set);

	if (err == -EBUSY) {
		pr_err("soct-dp: the pixel MMCM is not locked - cannot retune\n");
		return err;
	}
	if (err) {
		pr_err("soct-dp: the pixel MMCM did not relock at %u Hz\n",
		       set->achieved_hz);
		return err;
	}
	/* Give the pixel-domain reset synchronizer time to release after relock. */
	usleep_range(1000, 2000);
	return 0;
}

static int avbuf_select_live_video(void)
{
	XAVBuf_BlenderBgClr bg = { .RCr = 0xFFF, .GY = 0, .BCb = 0 };

	XAVBuf_CfgInitialize(&s.avbuf, PS_DP_BASEADDR, 0);
	/* DISABLEGFX, not NONE: the NONE enum value lands outside the stream-2 field. */
	XAVBuf_InputVideoSelect(&s.avbuf, XAVBUF_VIDSTREAM1_LIVE, XAVBUF_VIDSTREAM2_DISABLEGFX);
	XAVBuf_InputAudioSelect(&s.avbuf, XAVBUF_AUDSTREAM1_NO_AUDIO, XAVBUF_AUDSTREAM2_NO_AUDIO);
	if (XAVBuf_SetInputLiveVideoFormat(&s.avbuf, RGB_8BPC) != XST_SUCCESS)
		return -EINVAL;
	if (XAVBuf_SetOutputVideoFormat(&s.avbuf, RGB_8BPC) != XST_SUCCESS)
		return -EINVAL;
	XAVBuf_ConfigureVideoPipeline(&s.avbuf);
	XAVBuf_ConfigureOutputVideo(&s.avbuf);
	XAVBuf_SetBlenderAlpha(&s.avbuf, 0, 0);
	/* RED wherever no live video arrives: output path works, input does not. */
	XAVBuf_BlendSetBgColor(&s.avbuf, &bg);
	XAVBuf_SetAudioVideoClkSrc(&s.avbuf, XAVBUF_PL_CLK, XAVBUF_PS_CLK);
	return 0;
}

static int dp_link_up(void)
{
	unsigned int waited = 0;

	if (XDpPsu_InitializeTx(&s.dp) != XST_SUCCESS) {
		pr_err("soct-dp: DP TX initialization failed (PHY not ready) - did psu_init run this power-on?\n");
		return -EIO;
	}
	while (!XDpPsu_IsConnected(&s.dp)) {
		if (waited >= hpd_timeout_ms) {
			pr_warn("soct-dp: no DisplayPort monitor after %u ms - giving up (reload the module to retry)\n",
				hpd_timeout_ms);
			return -ENODEV;
		}
		msleep(100);
		waited += 100;
	}
	if (XDpPsu_GetRxCapabilities(&s.dp) != XST_SUCCESS) {
		pr_err("soct-dp: reading the monitor's DPCD capabilities failed (AUX channel)\n");
		return -EIO;
	}
	XDpPsu_SetEnhancedFrameMode(&s.dp, 1);
	XDpPsu_SetDownspread(&s.dp, 0);
	if (XDpPsu_CfgMainLinkMax(&s.dp) != XST_SUCCESS ||
	    XDpPsu_EstablishLink(&s.dp) != XST_SUCCESS) {
		pr_err("soct-dp: link training failed\n");
		return -EIO;
	}
	pr_info("soct-dp: link trained, %d Mbps per lane, %d lane(s)\n",
		270 * s.dp.LinkConfig.LinkRate, s.dp.LinkConfig.LaneCount);
	return 0;
}

/* Pushes the mode's main stream attributes over the trained link. The MSA is built
 * from the timing itself (XDpPsu_CfgMsaUseCustom), not a standard-mode table - the
 * caller's timing is the only source of modes. */
static void dp_start_stream(const struct soct_dp_mode *m)
{
	XDpPsu_MainStreamAttributes msa = { 0 };

	msa.PixelClockHz = m->pixel_clock_hz;
	msa.Vtm.VmId = XVIDC_VM_USE_EDID_PREFERRED; /* marker: not a table mode */
	msa.Vtm.FrameRate = m->fps;
	msa.Vtm.Timing.HActive = m->hactive;
	msa.Vtm.Timing.HFrontPorch = m->hfront_porch;
	msa.Vtm.Timing.HSyncWidth = m->hsync_len;
	msa.Vtm.Timing.HBackPorch = m->hback_porch;
	msa.Vtm.Timing.HTotal = mode_htotal(m);
	msa.Vtm.Timing.HSyncPolarity = m->hsync_active;
	msa.Vtm.Timing.VActive = m->vactive;
	msa.Vtm.Timing.F0PVFrontPorch = m->vfront_porch;
	msa.Vtm.Timing.F0PVSyncWidth = m->vsync_len;
	msa.Vtm.Timing.F0PVBackPorch = m->vback_porch;
	msa.Vtm.Timing.F0PVTotal = mode_vtotal(m);
	msa.Vtm.Timing.VSyncPolarity = m->vsync_active;

	XDpPsu_SetColorEncode(&s.dp, XDPPSU_CENC_RGB);
	XDpPsu_CfgMsaSetBpc(&s.dp, 8);
	XDpPsu_CfgMsaEnSynchClkMode(&s.dp, 1);
	XDpPsu_CfgMsaUseCustom(&s.dp, &msa, 1);
	/* Idle pattern while reconfiguring, then reset the TX and push the MSA. */
	XDpPsu_EnableMainLink(&s.dp, 0);
	XDpPsu_WriteReg(s.dp.Config.BaseAddr, XDPPSU_SOFT_RESET, 0x1);
	XDpPsu_WriteReg(s.dp.Config.BaseAddr, XDPPSU_SOFT_RESET, 0x0);
	XDpPsu_SetVideoMode(&s.dp);
	XDpPsu_EnableMainLink(&s.dp, 1);
}

/* Settles, then reads the video-out status flags (bit 0 locked, bit 1 underflow). */
static u32 video_status(void)
{
	msleep(100);
	return s.vidstat ? readl(s.vidstat) : 0;
}

const struct soct_dp_mode *soct_dp_current_mode(void)
{
	return &s.cur;
}

int soct_dp_validate_mode(struct soct_dp_mode *m)
{
	struct soct_clk_wzrd_setting set;
	int err;

	if (!m->hactive || !m->vactive || !m->fps || !m->pixel_clock_hz)
		return -EINVAL;
	if (!s.retunable) {
		/* Static design: only the synthesized mode exists. The clock tolerance
		 * absorbs the fbdev UAPI's picosecond round-trip. */
		struct soct_dp_mode t = *m;

		t.pixel_clock_hz = s.cur.pixel_clock_hz;
		if ((u64)abs_diff(m->pixel_clock_hz, s.cur.pixel_clock_hz) * 200 >
			    s.cur.pixel_clock_hz ||
		    !soct_dp_mode_equal(&t, &s.cur))
			return -EOPNOTSUPP;
		m->pixel_clock_hz = s.cur.pixel_clock_hz;
		return 0;
	}
	/* The ceiling: timing closure ran at the synthesized clock, so retunes only
	 * go down. A rounding-hair above (the picosecond round-trip again) clamps;
	 * genuinely above is refused. */
	if (m->pixel_clock_hz > s.max_hz + s.max_hz / 200)
		return -EDOM;
	if (m->pixel_clock_hz > s.max_hz)
		m->pixel_clock_hz = s.max_hz;
	err = soct_clk_wzrd_solve(&s.lim, m->pixel_clock_hz, &set);
	if (err)
		return err;
	if (set.achieved_hz > s.max_hz)
		return -EDOM;
	m->pixel_clock_hz = set.achieved_hz;
	return 0;
}

int soct_dp_switch_mode(const struct soct_dp_mode *m)
{
	struct soct_clk_wzrd_setting set;
	struct soct_dp_mode want = *m;
	bool retune;
	u32 flags;
	int err;

	err = soct_dp_validate_mode(&want);
	if (err)
		return err;
	if (soct_dp_mode_equal(&want, &s.cur))
		return 0;
	retune = want.pixel_clock_hz != s.cur.pixel_clock_hz;
	if (retune) {
		/* Solving for an achieved value lands on its own setting exactly. */
		err = soct_clk_wzrd_solve(&s.lim, want.pixel_clock_hz, &set);
		if (err)
			return err;
	}

	/* Halt the fetch first, while its stream clock still runs: the VDMA's soft
	 * reset needs all of its clocks, and the retune stops the pixel clock. */
	err = vdma_halt();
	if (err)
		goto halted;
	if (retune) {
		err = pixclk_retune(&set);
		if (err)
			goto halted; /* scanout stays halted - an honest, visible failure */
	}
	err = vdma_start(s.fb, want.hactive, want.vactive);
	if (err)
		goto halted;
	vtc_start(&want);
	dp_start_stream(&want);
	s.cur = want;

	/* Re-locking hunts for a few frames after a restart; poll instead of
	 * sampling once, so an unlocked report means genuinely unlocked. */
	if (s.vidstat) {
		if (read_poll_timeout(readl, flags, flags & 1u, 10000, 1000000,
				      false, s.vidstat))
			pr_warn("soct-dp: the video out did NOT lock after the switch (status=0x%x)\n",
				flags);
	} else {
		flags = 0;
	}
	pr_info("soct-dp: scanout switched to %ux%u@%u, %u Hz pixel clock (video out locked=%u underflow=%u)\n",
		want.hactive, want.vactive, want.fps, want.pixel_clock_hz,
		flags & 1u, (flags >> 1) & 1u);
	return 0;

halted:
	/* The pipeline is stopped and no longer matches the cached mode, so the cache
	 * is now a lie that would block the way out: asking again for the mode that was
	 * running is how a user recovers, and the equality check above would dismiss it
	 * as already current. Forgetting the mode also forces the clock comparison to
	 * re-drive the MMCM rather than assume it still holds the old frequency. */
	memset(&s.cur, 0, sizeof(s.cur));
	return err;
}

/* Parses the retunable-pixel-clock node's budget; absent node = static design
 * (every coherent design, and device trees older than this module). */
static void parse_pixclk(void)
{
	struct device_node *np = of_find_compatible_node(NULL, NULL, "soct,pixel-clkwiz");

	if (!np)
		return;
	if (of_property_read_u32(np, "soct,input-frequency", &s.lim.input_hz) ||
	    of_property_read_u32(np, "soct,max-frequency", &s.max_hz) ||
	    of_property_read_u32(np, "soct,vco-min", &s.lim.vco_min_hz) ||
	    of_property_read_u32(np, "soct,vco-max", &s.lim.vco_max_hz) ||
	    of_property_read_u32(np, "soct,pfd-min", &s.lim.pfd_min_hz) ||
	    of_property_read_u32(np, "soct,pfd-max", &s.lim.pfd_max_hz)) {
		pr_warn("soct-dp: the pixel-clkwiz node lacks the retune budget - resolution switching disabled\n");
		of_node_put(np);
		return;
	}
	s.pixclk = of_iomap(np, 0);
	of_node_put(np);
	if (!s.pixclk) {
		pr_warn("soct-dp: cannot map the pixel clock wizard - resolution switching disabled\n");
		return;
	}
	s.retunable = true;
}

/** ioremap register index 0 of the first node with `compat`; NULL when the node is absent. */
static void __iomem *iomap_compatible(const char *compat, struct device_node **np_out)
{
	struct device_node *np = of_find_compatible_node(NULL, NULL, compat);

	if (np_out)
		*np_out = np;
	if (!np)
		return NULL;
	return of_iomap(np, 0);
}

static void soct_dp_work(struct work_struct *work)
{
	struct device_node *vdma_np, *vtc_np, *win_np, *chosen, *fb_np;
	struct resource fb_res;
	u32 ps_base, fb_w, fb_h, fb_stride, flags;
	XDpPsu_Config cfg = { 0 };
	struct resource win_res;
	bool incoherent;

	s.vdma = iomap_compatible("xlnx,axi-vdma-1.00.a", &vdma_np);
	if (!s.vdma) {
		pr_info("soct-dp: no video pipeline in this design's device tree - nothing to do\n");
		return;
	}
	incoherent = of_property_present(vdma_np, "soct,incoherent");

	if (incoherent) {
		/* No /chosen node here (simplefb must not adopt an incoherent buffer);
		 * the raw carve-out is the framebuffer, served by this module's fbdev. */
		struct device_node *resv = of_find_node_by_path("/reserved-memory");

		fb_np = resv ? of_get_child_by_name(resv, "framebuffer") : NULL;
		if (!fb_np || of_address_to_resource(fb_np, 0, &fb_res)) {
			pr_err("soct-dp: no reserved framebuffer carve-out - the device tree is older than this module\n");
			return;
		}
	} else {
		/* The console framebuffer, from the same node simplefb reads. */
		chosen = of_find_node_by_path("/chosen");
		fb_np = chosen ? of_get_compatible_child(chosen, "simple-framebuffer") : NULL;
		if (!fb_np || of_address_to_resource(fb_np, 0, &fb_res)) {
			pr_err("soct-dp: no /chosen framebuffer node - the device tree is older than this module\n");
			return;
		}
	}

	s.vtc = iomap_compatible("xlnx,v-tc-6.2", &vtc_np);
	s.vidstat = iomap_compatible("soct,video-status", NULL);
	win_np = of_find_compatible_node(NULL, NULL, "soct,zynqmp-ps-window");
	if (!s.vtc || !s.vidstat || !win_np || of_address_to_resource(win_np, 0, &win_res) ||
	    of_property_read_u32(win_np, "soct,ps-base", &ps_base)) {
		pr_err("soct-dp: pipeline nodes missing or unreadable (vtc/vidstat/ps-window)\n");
		return;
	}
	s.window = ioremap(win_res.start, resource_size(&win_res));
	if (!s.window) {
		pr_err("soct-dp: cannot map the PS register window\n");
		return;
	}
	SoctXil_SetPsWindow(s.window, ps_base, resource_size(&win_res));

	/* The synthesized mode, complete timing included; the framebuffer node must
	 * agree with its geometry. */
	if (of_read_mode(vtc_np, &s.cur)) {
		pr_err("soct-dp: the vtc node does not carry the full video timing - the device tree is older than this module\n");
		return;
	}
	if (!incoherent &&
	    (of_property_read_u32(fb_np, "width", &fb_w) ||
	     of_property_read_u32(fb_np, "height", &fb_h) ||
	     of_property_read_u32(fb_np, "stride", &fb_stride) ||
	     fb_w != s.cur.hactive || fb_h != s.cur.vactive ||
	     fb_stride != s.cur.hactive * 3)) {
		pr_err("soct-dp: framebuffer node and video mode disagree - inconsistent device tree\n");
		return;
	}

	s.fb = fb_res.start;
	s.fb_size = resource_size(&fb_res);
	parse_pixclk();
	if (s.retunable && soct_dp_validate_mode(&s.cur)) {
		/* Normalizes the boot clock to the solver's achieved value, so later
		 * validations converge on it. Failure means the budget cannot even
		 * reproduce the synthesized clock - an inconsistent device tree. */
		pr_warn("soct-dp: the retune budget cannot reproduce the synthesized pixel clock - resolution switching disabled\n");
		s.retunable = false;
	}
	if (s.retunable) {
		/* The MMCM's programmed dividers survive a warm reboot, so after a
		 * kept mode switch the pixel clock still ticks at the OLD mode while
		 * everything downstream gets configured for the boot mode - the
		 * screen shows the mismatch as tearing/striping until something
		 * retunes. Never trust the inherited clock: program it. Nothing
		 * downstream runs yet, so no halt/restart dance is needed. */
		struct soct_clk_wzrd_setting set;

		if (soct_clk_wzrd_solve(&s.lim, s.cur.pixel_clock_hz, &set) ||
		    pixclk_retune(&set))
			pr_warn("soct-dp: cannot program the boot pixel clock - scanout may run off-frequency\n");
		else
			pr_info("soct-dp: boot pixel clock programmed to %u Hz (inherited state discarded)\n",
				set.achieved_hz);
	}

	pr_info("soct-dp: scanout %ux%u@%u from the console framebuffer at %pa%s\n",
		s.cur.hactive, s.cur.vactive, s.cur.fps, &fb_res.start,
		incoherent ? " (incoherent fetch, cache-flushing fbdev)" : "");
	if (s.retunable)
		pr_info("soct-dp: pixel clock retunable up to %u Hz\n", s.max_hz);

	/* Before the first fetch: the frame must be cleared and pushed out of the caches. */
	if (incoherent &&
	    soct_dp_fb_prepare(fb_res.start, resource_size(&fb_res), &s.cur))
		return;

	if (vdma_start(fb_res.start, s.cur.hactive, s.cur.vactive))
		return;
	vtc_start(&s.cur);

	cfg.BaseAddr = PS_DP_BASEADDR;
	XDpPsu_CfgInitialize(&s.dp, &cfg, cfg.BaseAddr);
	if (avbuf_select_live_video()) {
		pr_err("soct-dp: AVBuf rejected the live video format\n");
		return;
	}
	if (dp_link_up())
		return;
	dp_start_stream(&s.cur);

	flags = video_status();
	pr_info("soct-dp: display is up (video out locked=%u underflow=%u)\n",
		flags & 1u, (flags >> 1) & 1u);
	if (!(flags & 1u))
		pr_warn("soct-dp: the video out is NOT locked - no video is leaving the PL\n");

	if (incoherent)
		soct_dp_fb_register();
}

static DECLARE_WORK(soct_dp_bringup, soct_dp_work);

static int __init soct_dp_init(void)
{
	schedule_work(&soct_dp_bringup);
	return 0;
}

static void __exit soct_dp_exit(void)
{
	cancel_work_sync(&soct_dp_bringup);
	soct_dp_fb_teardown();
	/* Stop the scanout cleanly; the monitor loses its signal, the framebuffer stays. */
	if (s.vdma)
		writel(VDMA_DMACR_RESET, s.vdma + VDMA_MM2S_DMACR);
	if (s.vdma)
		iounmap(s.vdma);
	if (s.vtc)
		iounmap(s.vtc);
	if (s.vidstat)
		iounmap(s.vidstat);
	if (s.window)
		iounmap(s.window);
	if (s.pixclk)
		iounmap(s.pixclk);
}

module_init(soct_dp_init);
module_exit(soct_dp_exit);

MODULE_DESCRIPTION("SoCeteer DisplayPort pipeline bring-up (scanout for the console framebuffer)");
MODULE_LICENSE("Dual MIT/GPL");
