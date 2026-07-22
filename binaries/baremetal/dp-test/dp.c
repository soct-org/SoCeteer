#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "dp.h"
#include "sleep.h"
#include "xavbuf.h"
#include "xil_io.h"
#include "xstatus.h"

/* Mode the stream is configured for, remembered by dp_open() so the recovery
 * paths can re-enable the stream without carrying it around. */
static XVidC_VideoMode s_mode;

/* =========================================================================
 * PS window probe
 *
 * A broken path to the PS registers does not fail loudly on its own: a dead
 * AXI route typically reads as all-ones, which makes every "poll until bits
 * set" check in the Xilinx driver pass vacuously (PHY "ready", monitor
 * "connected", link "trained") - or as all-zeros, which hangs the PHY poll.
 * A write/readback round-trip proves the registers actually respond.
 * ========================================================================= */

void dp_probe_ps_window(void) {
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
 * AVBuf: select the PL's live video as the blender input
 * ========================================================================= */

void dp_avbuf_select_live_video(void) {
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
 * DisplayPort link + stream (vendored dppsu drivers)
 * ========================================================================= */

void dp_open(XDpPsu *dp, XVidC_VideoMode mode) {
    XDpPsu_Config cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.BaseAddr = PS_DP_BASEADDR;
    XDpPsu_CfgInitialize(dp, &cfg, cfg.BaseAddr);
    s_mode = mode;
}

void dp_start_link(XDpPsu *dp) {
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

void dp_start_stream(XDpPsu *dp) {
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

void dp_report_edid(XDpPsu *dp) {
    u8 edid[XDPPSU_EDID_BLOCK_SIZE];
    if (XDpPsu_GetEdid(dp, edid) == XST_SUCCESS) {
        printf("EDID: manufacturer bytes %02x %02x, product %02x%02x\n",
               edid[8], edid[9], edid[11], edid[10]);
    } else {
        printf("note: EDID read failed (non-fatal, continuing)\n");
    }
}
