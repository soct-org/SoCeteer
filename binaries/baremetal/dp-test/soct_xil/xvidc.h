/*
 * Minimal replacement for the Xilinx video_common library (xvidc), providing exactly
 * what the vendored dppsu sources use: the timing structures and a timing table with
 * the video modes this design generates. Struct layout and table values match
 * video_common; the enum is cut down to the supported modes (it indexes the table,
 * so values must stay sequential from 0).
 */
#ifndef XVIDC_H_
#define XVIDC_H_

#include "xil_types.h"

typedef enum {
    XVIDC_VM_640x480_60_P = 0,
    XVIDC_VM_1280x720_60_P,
    XVIDC_VM_1920x1080_60_P,
    XVIDC_VM_NUM_SUPPORTED,
    XVIDC_VM_USE_EDID_PREFERRED,
} XVidC_VideoMode;

typedef enum {
    XVIDC_FR_60HZ = 60,
} XVidC_FrameRate;

typedef struct {
    u16 HActive;
    u16 HFrontPorch;
    u16 HSyncWidth;
    u16 HBackPorch;
    u16 HTotal;
    u8 HSyncPolarity;
    u16 VActive;
    u16 F0PVFrontPorch;
    u16 F0PVSyncWidth;
    u16 F0PVBackPorch;
    u16 F0PVTotal;
    u16 F1VFrontPorch;
    u16 F1VSyncWidth;
    u16 F1VBackPorch;
    u16 F1VTotal;
    u8 VSyncPolarity;
} XVidC_VideoTiming;

typedef struct {
    XVidC_VideoMode VmId;
    char Name[21];
    XVidC_FrameRate FrameRate;
    XVidC_VideoTiming Timing;
} XVidC_VideoTimingMode;

u64 XVidC_GetPixelClockHzByVmId(XVidC_VideoMode VmId);

#endif /* XVIDC_H_ */
