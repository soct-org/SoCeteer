/*
 * Implementation of the kernel shim for the vendored Xilinx sources (see the headers in
 * this directory): PS-window register translation and the video timing table.
 */
#include <linux/io.h>
#include <linux/kernel.h>

#include "xil_io.h"
#include "xvidc.h"

static void __iomem *s_window_virt;
static UINTPTR s_ps_base;
static UINTPTR s_window_size;

void SoctXil_SetPsWindow(void __iomem *window_virt, UINTPTR ps_base, UINTPTR window_size)
{
    s_window_virt = window_virt;
    s_ps_base = ps_base;
    s_window_size = window_size;
}

/* Translate a PS-space register address into the mapped window. Everything the vendored
 * sources touch is a PS register, so anything else is a driver bug - warn with a
 * backtrace rather than dereference a wild address. */
static void __iomem *translate(UINTPTR addr)
{
    if (s_window_size && addr >= s_ps_base && addr - s_ps_base < s_window_size)
        return s_window_virt + (addr - s_ps_base);
    WARN_ONCE(1, "soct-dp: register 0x%lx is outside the PS window (base 0x%lx, size 0x%lx)",
              (unsigned long)addr, (unsigned long)s_ps_base, (unsigned long)s_window_size);
    return NULL;
}

u32 Xil_In32(UINTPTR Addr)
{
    void __iomem *p = translate(Addr);

    return p ? readl(p) : 0;
}

void Xil_Out32(UINTPTR Addr, u32 Value)
{
    void __iomem *p = translate(Addr);

    if (p)
        writel(Value, p);
}

/* Timing values taken from video_common's xvidc_timings_table.c (CEA-861/DMT); the same
 * table the bare-metal shim carries. */
const XVidC_VideoTimingMode XVidC_VideoTimingModes[XVIDC_VM_NUM_SUPPORTED] = {
    {XVIDC_VM_640x480_60_P, "640x480@60Hz", XVIDC_FR_60HZ,
     {640, 16, 96, 48, 800, 0, 480, 10, 2, 33, 525, 0, 0, 0, 0, 0}},
    {XVIDC_VM_1280x720_60_P, "1280x720@60Hz", XVIDC_FR_60HZ,
     {1280, 110, 40, 220, 1650, 1, 720, 5, 5, 20, 750, 0, 0, 0, 0, 1}},
    {XVIDC_VM_1920x1080_60_P, "1920x1080@60Hz", XVIDC_FR_60HZ,
     {1920, 88, 44, 148, 2200, 1, 1080, 4, 5, 36, 1125, 0, 0, 0, 0, 1}},
};

u64 XVidC_GetPixelClockHzByVmId(XVidC_VideoMode VmId)
{
    const XVidC_VideoTiming *t;

    if (VmId >= XVIDC_VM_NUM_SUPPORTED) {
        WARN_ONCE(1, "soct-dp: unsupported video mode %d", (int)VmId);
        return 0;
    }
    t = &XVidC_VideoTimingModes[VmId].Timing;
    return (u64)t->HTotal * t->F0PVTotal * XVidC_VideoTimingModes[VmId].FrameRate;
}
