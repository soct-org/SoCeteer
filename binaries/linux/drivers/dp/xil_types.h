/*
 * Kernel replacement for the Xilinx BSP's xil_types.h: u8..u64/s8..s64 are the kernel's
 * own, only the pointer-sized aliases and constants the vendored sources use are added.
 */
#ifndef XIL_TYPES_H
#define XIL_TYPES_H

#include <linux/types.h>

typedef uintptr_t UINTPTR;
typedef intptr_t INTPTR;

#define XIL_COMPONENT_IS_READY 0x11111111U

#ifndef TRUE
#define TRUE 1U
#endif
#ifndef FALSE
#define FALSE 0U
#endif

#endif /* XIL_TYPES_H */
