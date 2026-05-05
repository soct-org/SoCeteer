#pragma once


#define SOCT_DTB_MAX_SIZE 0x10000 // 64KB
#define SOCT_UART_NAME_DTS "riscv,axi-uart-1.0"
#define SOCT_SDC_NAME_DTS "riscv,axi-sd-card-1.0"
#define SOCT_N_SYSCALL_HANDLERS 64
#define SOCT_MAX_HARTS 64 // Maximum number of harts supported; used for static array sizing
#define SOCT_N_SETUP_MSGS 16
#define SOCT_ARG_MAX 128

// Fallback addresses if dtb is not available
#define SOCT_DEFAULT_UART_ADDR 0x60010000
#define SOCT_DEFAULT_SDC_ADDR 0x60000000

// HTIF Constants
#define SOCT_HTIF_DEV_SHIFT      56
#define SOCT_HTIF_DEV_MASK       0xff
#define SOCT_HTIF_CMD_SHIFT      48
#define SOCT_HTIF_CMD_MASK       0xff
#define SOCT_HTIF_PAYLOAD_MASK   ((1UL << SOCT_HTIF_CMD_SHIFT) - 1)
#define SOCT_HTIF_SECTION ".htif"
#define SOCT_HTIF_PROBE_TIMEOUT_CYCLES 100000
