/*
 * Register-access shim for the vendored Xilinx sources, kernel flavor.
 *
 * The PS register space (DisplayPort controller, AVBuf, SERDES - fixed 0xFDxx_xxxx
 * addresses) is reached through the design's address window (the soct,zynqmp-ps-window
 * node), ioremapped once at module start. Xil_In32/Out32 translate the drivers' hardcoded
 * PS addresses through that mapping, so the vendored sources run unmodified.
 */
#ifndef XIL_IO_H
#define XIL_IO_H

/* The BSP's xil_io.h transitively provides these; the vendored sources rely on that. */
#include <linux/string.h>

#include "xil_assert.h"
#include "xil_types.h"
#include "xparameters.h"
#include "xstatus.h"

/* Must be called once before any register access; `window_virt` maps `window_size` bytes
 * of the PS address space starting at PS address `ps_base`. */
void SoctXil_SetPsWindow(void __iomem *window_virt, UINTPTR ps_base, UINTPTR window_size);

u32 Xil_In32(UINTPTR Addr);
void Xil_Out32(UINTPTR Addr, u32 Value);

#endif /* XIL_IO_H */
