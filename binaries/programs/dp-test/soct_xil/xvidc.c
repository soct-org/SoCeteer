#include "xvidc.h"

#include <stdio.h>
#include <stdlib.h>

/* Timing values taken from video_common's xvidc_timings_table.c (CEA-861/DMT). */
const XVidC_VideoTimingMode XVidC_VideoTimingModes[XVIDC_VM_NUM_SUPPORTED] = {
    {XVIDC_VM_640x480_60_P, "640x480@60Hz", XVIDC_FR_60HZ,
     {640, 16, 96, 48, 800, 0, 480, 10, 2, 33, 525, 0, 0, 0, 0, 0}},
    {XVIDC_VM_1280x720_60_P, "1280x720@60Hz", XVIDC_FR_60HZ,
     {1280, 110, 40, 220, 1650, 1, 720, 5, 5, 20, 750, 0, 0, 0, 0, 1}},
    {XVIDC_VM_1920x1080_60_P, "1920x1080@60Hz", XVIDC_FR_60HZ,
     {1920, 88, 44, 148, 2200, 1, 1080, 4, 5, 36, 1125, 0, 0, 0, 0, 1}},
};

u64 XVidC_GetPixelClockHzByVmId(XVidC_VideoMode VmId) {
    if (VmId >= XVIDC_VM_NUM_SUPPORTED) {
        printf("XVidC_GetPixelClockHzByVmId: unsupported video mode %d\n", (int)VmId);
        abort();
    }
    const XVidC_VideoTiming *t = &XVidC_VideoTimingModes[VmId].Timing;
    return (u64)t->HTotal * t->F0PVTotal * XVidC_VideoTimingModes[VmId].FrameRate;
}
