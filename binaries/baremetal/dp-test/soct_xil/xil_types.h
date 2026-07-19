/*
 * Minimal replacement for the Xilinx BSP's xil_types.h, so the vendored dppsu/avbuf
 * sources in ../xilinx compile unmodified on the SoCeteer RISC-V toolchain.
 */
#ifndef XIL_TYPES_H
#define XIL_TYPES_H

#include <stdint.h>

typedef uint8_t u8;
typedef uint16_t u16;
typedef uint32_t u32;
typedef uint64_t u64;
typedef int8_t s8;
typedef int16_t s16;
typedef int32_t s32;
typedef int64_t s64;
typedef uintptr_t UINTPTR;
typedef intptr_t INTPTR;

#define XIL_COMPONENT_IS_READY 0x11111111U

#ifndef TRUE
#define TRUE 1U
#endif
#ifndef FALSE
#define FALSE 0U
#endif
#ifndef NULL
#define NULL ((void *)0)
#endif

#endif /* XIL_TYPES_H */
