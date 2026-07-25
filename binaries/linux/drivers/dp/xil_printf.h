/* Print shim for the vendored Xilinx sources: their diagnostics become kernel log lines. */
#ifndef XIL_PRINTF_H
#define XIL_PRINTF_H

#include <linux/printk.h>

#define xil_printf(fmt, ...) printk(KERN_INFO "soct-dp: " fmt, ##__VA_ARGS__)

#endif /* XIL_PRINTF_H */
