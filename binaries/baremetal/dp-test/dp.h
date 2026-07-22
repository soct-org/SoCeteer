/*
 * PS DisplayPort: register window probe, AVBuf routing, link and stream.
 *
 * The PS DisplayPort controller is programmed through the vendored Xilinx
 * dppsu/avbuf drivers (see xilinx/), whose PS register addresses are
 * translated through the design's address window (dpwin0 device-tree node).
 *
 * Prerequisite: the PS must have been initialized once after power-up
 * (clocks, DP SERDES/PS-GTR lanes) by running the psu_init that Vivado
 * generates alongside the design - without it the DP PHY never reports ready.
 */
#ifndef DP_TEST_DP_H
#define DP_TEST_DP_H

#include "xdppsu.h"
#include "xvidc.h"

/* PS-fixed base of the DP controller (AVBuf registers included) - reached
 * through the dpwin0 window, see xil_io.h. */
#define PS_DP_BASEADDR 0xFD4A0000u

/* Write/readback round-trip proving the PS registers actually respond; aborts
 * when they do not (see dp.c for why a dead path is otherwise invisible). */
void dp_probe_ps_window(void);

/* Route the PL's live video through the AVBuf blender to the DP output. */
void dp_avbuf_select_live_video(void);

/* Bind the driver to the controller and remember the video mode the stream
 * will be configured for. */
void dp_open(XDpPsu *dp, XVidC_VideoMode mode);

/* Bring up the PHY, wait for a monitor, and train the main link. */
void dp_start_link(XDpPsu *dp);

/* (Re)configure and enable the main stream for the mode passed to dp_open. */
void dp_start_stream(XDpPsu *dp);

/* Read and print a few identifying EDID bytes; non-fatal when it fails. */
void dp_report_edid(XDpPsu *dp);

#endif /* DP_TEST_DP_H */
