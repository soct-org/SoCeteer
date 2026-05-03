#pragma once

#include "soctglue.h"
#include "soct/syscall-handler.h"
#include "syscall-uart.h"
#include "soct/smoldtb.h"
#include "soct/defaults.h"
#include "soct/soct_ff.h"

#define SR_RX_FIFO_VALID_DATA   (1 << 0) /* data in receive FIFO */
#define SR_RX_FIFO_FULL         (1 << 1) /* receive FIFO full */
#define SR_TX_FIFO_EMPTY        (1 << 2) /* transmit FIFO empty */
#define SR_TX_FIFO_FULL         (1 << 3) /* transmit FIFO full */

static uintptr_t s_uart_base = SOCT_DEFAULT_UART_ADDR;

struct uart_regs {
    volatile uint32_t rx_fifo;
    volatile uint32_t tx_fifo;
    volatile uint32_t status;
    volatile uint32_t control;
};


// Write char to UART
static void kputc(const char ch) {
    if (ch == '\n') kputc('\r'); // convert LF to CRLF
    struct uart_regs *regs = (struct uart_regs *) s_uart_base;
    while (regs->status & SR_TX_FIFO_FULL) {
    }
    regs->tx_fifo = ch & 0xff;
}


// Write string to UART
static void kputs(const char *s, const size_t nbyte) {
    if (nbyte == 0) {
        while (*s) kputc(*s++);
    } else {
        for (size_t i = 0; i < nbyte; i++) {
            kputc(s[i]);
        }
    }
}

// Read char from UART
static char kgetc() {
    struct uart_regs *regs = (struct uart_regs *) s_uart_base;
    while (!(regs->status & SR_RX_FIFO_VALID_DATA)) {
    }
    return regs->rx_fifo & 0xff;
}


/**
 * Initialize the UART handler by parsing the DTB for the UART node and extracting its base address.
 * This should be called during the initialization phase of the soct glue code, before any syscalls are handled.
 * @return Whether the initialization was successful and the handler should be used.
 */
static bool soct_init_from_dtb_uart() {
    dtb_node *node = dtb_find_compatible(NULL, SOCT_UART_NAME_DTS);
    if (!node) {
        soct_add_setup_msg("No UART node found in DTB - UART handler disabled");
        return false;
    }
    dtb_prop *reg_prop = dtb_find_prop(node, "reg");
    if (!reg_prop) {
        soct_add_setup_msg("UART node found in DTB but it has no 'reg' property");
        return false;
    }
    const size_t addr_cells = dtb_get_addr_cells_for(node);
    uintmax_t regs[16] = {0};
    const size_t n = dtb_read_prop_1(reg_prop, addr_cells, regs);
    if (n > 1) {
        s_uart_base = regs[0];
        char msg[128];
        snprintf(msg, sizeof(msg), "UART node found in DTB with base address 0x%jx", s_uart_base);
        soct_add_setup_msg(msg);
        return true;
    }
    soct_add_setup_msg("UART node found in DTB but failed to read base address from 'reg' property");
    return false;
}


static void soct_handle_uart(
    soct_handler_resp_t *resp,
    const sc_type_t syscall,
    const sc_arg_t a0,
    const sc_arg_t a1,
    const sc_arg_t a2,
    const sc_arg_t a3,
    const sc_arg_t a4,
    const sc_arg_t a5,
    const sc_arg_t a6) {
    (void) a3;
    (void) a4;
    (void) a5;
    (void) a6;
    switch (syscall) {
        case SOCT_READ:
            if (a0 == SOCT_STDIN) {
                char *buf = (char *) a1;
                const size_t nbyte = a2;
                for (size_t i = 0; i < nbyte; i++) {
                    buf[i] = kgetc();
                }
                resp->status = SOCT_HANDLER_HANDLED;
                resp->ret = (long) nbyte; // Return number of bytes read
            }
            break;
        case SOCT_WRITE:
            if (a0 == SOCT_STDOUT || a0 == SOCT_STDERR) {
                kputs((const char *) a1, a2);
                resp->status = SOCT_HANDLER_HANDLED;
                resp->ret = (long) a2; // Return number of bytes written
            }
            break;
        default:
            break; // Not handled, pass to next handler
    }
}
