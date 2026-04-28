#pragma once
#include "syscall-handler.h"

void soct_handle_sdc(
    soct_handler_resp_t *resp,
    uint32_t syscall,
    uint64_t a0,
    uint64_t a1,
    uint64_t a2,
    uint64_t a3,
    uint64_t a4,
    uint64_t a5,
    uint64_t a6);


bool soct_init_from_dtb_sdc();
