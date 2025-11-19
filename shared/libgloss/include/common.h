#pragma once

// Must be aligned by 0x10000
// Assuming at least 16M of memory
#define BOOT_MEM_ADDR    0x80000000
#define BOOT_MEM_END     0x81000000

// Define reg_t type if not assembly
#ifndef __ASSEMBLY__
#include <stdint.h>
#if defined(__riscv_xlen) && __riscv_xlen == 64
typedef uint64_t reg_t;
#else
typedef uint32_t reg_t;
#endif
#endif