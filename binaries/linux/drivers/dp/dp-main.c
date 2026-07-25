// SPDX-License-Identifier: GPL-2.0
/*
 * soct-dp: brings the SoCeteer DisplayPort pipeline up and leaves it scanning the
 * console framebuffer.
 *
 * The pipeline is framebuffer (DRAM) -> AXI VDMA -> AXI4-Stream video out (+ VTC
 * timing) -> PS DP live video input -> DP main link -> monitor, and every stage is
 * discovered from the device tree. The framebuffer is the fixed reservation the
 * /chosen/framebuffer node describes: simplefb registers it and fbcon renders the
 * console into it independently of this module - this module only starts the hardware
 * that puts it on the monitor. The two meet at that node, so they cannot disagree on
 * the address.
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
#include <linux/module.h>
#include <linux/of.h>
#include <linux/of_address.h>
#include <linux/workqueue.h>

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
	XDpPsu dp;
	XAVBuf avbuf;
} s;

static int vdma_start(phys_addr_t fb, u32 width, u32 height)
{
	unsigned int i;
	u32 sr;

	writel(VDMA_DMACR_RESET, s.vdma + VDMA_MM2S_DMACR);
	if (read_poll_timeout(readl, sr, !(sr & VDMA_DMACR_RESET), 100, 100000,
			      false, s.vdma + VDMA_MM2S_DMACR)) {
		pr_err("soct-dp: VDMA reset did not complete\n");
		return -EIO;
	}

	/* Keep the reset defaults EXCEPT Circular_Park, whose reset value is 1: cleared, the
	 * engine parks on the store PARK_PTR selects instead of cycling all of them. All
	 * stores carry the one framebuffer - there is nothing to flip to. */
	writel((readl(s.vdma + VDMA_MM2S_DMACR) | VDMA_DMACR_RS) & ~VDMA_DMACR_CIRCULAR,
	       s.vdma + VDMA_MM2S_DMACR);
	writel(0, s.vdma + VDMA_PARK_PTR);
	for (i = 0; i < VDMA_NUM_FSTORES; i++)
		writel(lower_32_bits(fb), s.vdma + VDMA_MM2S_START_ADDR1 + 4 * i);
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

static void vtc_start(const XVidC_VideoTiming *t)
{
	const u32 hss = (u32)t->HActive + t->HFrontPorch;
	const u32 hbs = hss + t->HSyncWidth;
	const u32 vss = (u32)t->VActive + t->F0PVFrontPorch;
	const u32 vbs = vss + t->F0PVSyncWidth;

	writel(t->HTotal, s.vtc + VTC_GHSIZE);
	writel((u32)t->F0PVTotal | ((u32)t->F0PVTotal << 16), s.vtc + VTC_GVSIZE);
	writel((u32)t->HActive | ((u32)t->VActive << 16), s.vtc + VTC_GASIZE);
	writel((u32)t->VActive << 16, s.vtc + VTC_GASIZE_F1);
	writel(hss | (hbs << 16), s.vtc + VTC_GHSYNC);
	writel(vss | (vbs << 16), s.vtc + VTC_GVSYNC);
	writel(vss | (vbs << 16), s.vtc + VTC_GVSYNC_F1);
	writel(0, s.vtc + VTC_GFENC); /* progressive */
	writel((u32)t->HActive | ((u32)t->HActive << 16), s.vtc + VTC_GVBHOFF);
	writel(hss | (hss << 16), s.vtc + VTC_GVSHOFF);
	writel((u32)t->HActive | ((u32)t->HActive << 16), s.vtc + VTC_GVBHOFF_F1);
	writel(hss | (hss << 16), s.vtc + VTC_GVSHOFF_F1);
	writel(VTC_POL_BASE | (t->HSyncPolarity ? VTC_POL_HSP : 0)
	       | (t->VSyncPolarity ? VTC_POL_VSP : 0), s.vtc + VTC_GPOL);
	writel(VTC_CTL_ALLSS | VTC_CTL_RU | VTC_CTL_GE | VTC_CTL_SW, s.vtc + VTC_CTL);
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

static void dp_start_stream(XVidC_VideoMode mode)
{
	XDpPsu_SetColorEncode(&s.dp, XDPPSU_CENC_RGB);
	XDpPsu_CfgMsaSetBpc(&s.dp, 8);
	XDpPsu_CfgMsaEnSynchClkMode(&s.dp, 1);
	XDpPsu_CfgMsaUseStandardVideoMode(&s.dp, mode);
	/* Idle pattern while reconfiguring, then reset the TX and push the MSA. */
	XDpPsu_EnableMainLink(&s.dp, 0);
	XDpPsu_WriteReg(s.dp.Config.BaseAddr, XDPPSU_SOFT_RESET, 0x1);
	XDpPsu_WriteReg(s.dp.Config.BaseAddr, XDPPSU_SOFT_RESET, 0x0);
	XDpPsu_SetVideoMode(&s.dp);
	XDpPsu_EnableMainLink(&s.dp, 1);
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
	u32 ps_base, width, height, fps, fb_w, fb_h, fb_stride, flags;
	XVidC_VideoMode mode = XVIDC_VM_NUM_SUPPORTED;
	XDpPsu_Config cfg = { 0 };
	struct resource win_res;
	int m;

	s.vdma = iomap_compatible("xlnx,axi-vdma-1.00.a", &vdma_np);
	if (!s.vdma) {
		pr_info("soct-dp: no video pipeline in this design's device tree - nothing to do\n");
		return;
	}
	if (of_property_present(vdma_np, "soct,incoherent")) {
		pr_err("soct-dp: this design's frame fetch is incoherent; a console framebuffer is written through the CPU caches and would never be seen. Use the coherent video design.\n");
		return;
	}

	/* The console framebuffer, from the same node simplefb reads. */
	chosen = of_find_node_by_path("/chosen");
	fb_np = chosen ? of_get_compatible_child(chosen, "simple-framebuffer") : NULL;
	if (!fb_np || of_address_to_resource(fb_np, 0, &fb_res)) {
		pr_err("soct-dp: no /chosen framebuffer node - the device tree is older than this module\n");
		return;
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

	/* The mode is baked into the design; the framebuffer node must agree with it. */
	if (of_property_read_u32(vtc_np, "soct,hactive", &width) ||
	    of_property_read_u32(vtc_np, "soct,vactive", &height) ||
	    of_property_read_u32(vtc_np, "soct,fps", &fps)) {
		pr_err("soct-dp: the vtc node does not carry the video mode\n");
		return;
	}
	if (of_property_read_u32(fb_np, "width", &fb_w) ||
	    of_property_read_u32(fb_np, "height", &fb_h) ||
	    of_property_read_u32(fb_np, "stride", &fb_stride) ||
	    fb_w != width || fb_h != height || fb_stride != width * 3) {
		pr_err("soct-dp: framebuffer node and video mode disagree - inconsistent device tree\n");
		return;
	}
	for (m = 0; m < XVIDC_VM_NUM_SUPPORTED; m++) {
		if (XVidC_VideoTimingModes[m].Timing.HActive == width &&
		    XVidC_VideoTimingModes[m].Timing.VActive == height &&
		    (u32)XVidC_VideoTimingModes[m].FrameRate == fps)
			mode = XVidC_VideoTimingModes[m].VmId;
	}
	if (mode == XVIDC_VM_NUM_SUPPORTED) {
		pr_err("soct-dp: video mode %ux%u@%u is not in the timing table\n",
		       width, height, fps);
		return;
	}

	pr_info("soct-dp: scanout %ux%u@%u from the console framebuffer at %pa\n",
		width, height, fps, &fb_res.start);

	if (vdma_start(fb_res.start, width, height))
		return;
	vtc_start(&XVidC_VideoTimingModes[mode].Timing);

	cfg.BaseAddr = PS_DP_BASEADDR;
	XDpPsu_CfgInitialize(&s.dp, &cfg, cfg.BaseAddr);
	if (avbuf_select_live_video()) {
		pr_err("soct-dp: AVBuf rejected the live video format\n");
		return;
	}
	if (dp_link_up())
		return;
	dp_start_stream(mode);

	msleep(100);
	flags = s.vidstat ? readl(s.vidstat) : 0;
	pr_info("soct-dp: display is up (video out locked=%u underflow=%u)\n",
		flags & 1u, (flags >> 1) & 1u);
	if (!(flags & 1u))
		pr_warn("soct-dp: the video out is NOT locked - no video is leaving the PL\n");
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
}

module_init(soct_dp_init);
module_exit(soct_dp_exit);

MODULE_DESCRIPTION("SoCeteer DisplayPort pipeline bring-up (scanout for the console framebuffer)");
MODULE_LICENSE("Dual MIT/GPL");
