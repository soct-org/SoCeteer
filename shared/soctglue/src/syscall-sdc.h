#pragma once
#include "syscall-handler.h"

void soct_handle_sdc(
    soct_handler_resp_t *resp,
    sc_type_t syscall,
    sc_arg_t a0,
    sc_arg_t a1,
    sc_arg_t a2,
    sc_arg_t a3,
    sc_arg_t a4,
    sc_arg_t a5,
    sc_arg_t a6);


bool soct_init_from_dtb_sdc();
