#pragma once
#include <stdbool.h>
#include "soct/syscall-handler.h"

void soct_add_setup_msg(const char *msg);

/**
 * Initialize the UART handler by parsing the DTB for the UART node and extracting its base address.
 * This should be called during the initialization phase of the soct glue code, before any syscalls are handled.
 * @return Whether the initialization was successful and the handler should be used.
 */
bool soct_init_from_dtb_uart();


void soct_handle_uart(
    soct_handler_resp_t *resp,
    sc_type_t syscall,
    sc_arg_t a0,
    sc_arg_t a1,
    sc_arg_t a2,
    sc_arg_t a3,
    sc_arg_t a4,
    sc_arg_t a5,
    sc_arg_t a6
);


bool soct_htif_present(void);

void soct_handle_htif(
    soct_handler_resp_t *resp,
    sc_type_t syscall,
    sc_arg_t a0,
    sc_arg_t a1,
    sc_arg_t a2,
    sc_arg_t a3,
    sc_arg_t a4,
    sc_arg_t a5,
    sc_arg_t a6
);
