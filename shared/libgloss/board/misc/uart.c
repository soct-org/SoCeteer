#include <string.h>
#include <stdio.h>
#include "common.h"
#include "smoldtb/smoldtb.h"

#define SR_RX_FIFO_VALID_DATA   (1 << 0) /* data in receive FIFO */
#define SR_RX_FIFO_FULL         (1 << 1) /* receive FIFO full */
#define SR_TX_FIFO_EMPTY        (1 << 2) /* transmit FIFO empty */
#define SR_TX_FIFO_FULL         (1 << 3) /* transmit FIFO full */

static uintptr_t s_uart_base = 0x60010000; // Default UART base address

// Xilinx AXI UART registers
struct uart_regs {
    volatile uint32_t rx_fifo;
    volatile uint32_t tx_fifo;
    volatile uint32_t status;
    volatile uint32_t control;
};

uintptr_t __get_axi_uart_address() { // Requires dtb to be parsed
    dtb_node* uart_node = dtb_find_compatible(NULL, "riscv,axi-uart-1.0");
    if (!uart_node) {
        printf("UART node not found in DTB - using default address\n");
        return 0;
    }
    dtb_prop* reg_prop = dtb_find_prop(uart_node, "reg");
    if (!reg_prop) {
        printf("UART reg property not found in DTB - using default address\n");
        return 0;
    }

    const size_t addr_cells = dtb_get_addr_cells_for(uart_node);

    uintmax_t base;
    dtb_read_prop_1(reg_prop, addr_cells, &base);
    return base;
}


void __init_uart(const uint32_t hart) {
    if (hart != 0) {
        return; // Only initialize UART for hart 0
    }
    const uintptr_t uart_base = __get_axi_uart_address();
    if (uart_base) {
        s_uart_base = uart_base;
    }
}

// Write char to UART
void kputc(const char ch) {
    if (ch == '\n') kputc('\r'); // convert LF to CRLF
    struct uart_regs * regs = (struct uart_regs *)s_uart_base;
    while (regs->status & SR_TX_FIFO_FULL) {}
    regs->tx_fifo = ch & 0xff;
}

// Write string to UART
void kputs(const char * s, size_t len) {
    if (len == 0) {
        while (*s) kputc(*s++);
    } else {
        for (size_t i = 0; i < len; i++) {
            kputc(s[i]);
        }
    }
}

// Read char from UART
char kgetc() {
    struct uart_regs * regs = (struct uart_regs *)s_uart_base;
    while (!(regs->status & SR_RX_FIFO_VALID_DATA)) {}
    return regs->rx_fifo & 0xff;
}