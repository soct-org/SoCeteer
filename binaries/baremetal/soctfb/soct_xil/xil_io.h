/*
 * Register access shim for the vendored Xilinx sources.
 *
 * The PS register space (DisplayPort controller, AVBuf, SERDES - all at fixed
 * 0xFDxx_xxxx addresses) is not directly visible to the RISC-V. The design maps a
 * window of it into the MMIO space (see the dpwin0 device-tree node, compatible
 * "soct,zynqmp-dp-window": reg = window base/size, soct,ps-base = the PS address the
 * window start maps to). Xil_In32/Xil_Out32 translate PS addresses through that
 * window so the vendored sources keep using the documented PS addresses.
 *
 * Addresses below the PS range (the PL peripherals: VDMA, VTC) pass through
 * untranslated. A PS address outside the mapped window is a hard error.
 */
#ifndef XIL_IO_H
#define XIL_IO_H

/* The BSP's xil_io.h transitively provides these; the vendored sources rely on that. */
#include <string.h>
#include "xil_types.h"
#include "xparameters.h"
#include "xil_assert.h"
#include "xstatus.h"

/* Must be called once before any register access; values come from the DTB. */
void SoctXil_SetPsWindow(uintptr_t ps_base, uintptr_t window_base, uintptr_t window_size);

u32 Xil_In32(UINTPTR Addr);
void Xil_Out32(UINTPTR Addr, u32 Value);

#endif /* XIL_IO_H */
