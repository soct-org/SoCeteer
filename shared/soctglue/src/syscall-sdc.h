#pragma once
#include "syscall-handler.h"

// Proper implementation only if SOCT_NEEDS_FATFS is set and syscall-sdc.c is in the sources
inline void soct_handle_sdc(
    soct_handler_resp_t *resp,
    const uint32_t syscall,
    const uint64_t a0,
    const uint64_t a1,
    const uint64_t a2,
    const uint64_t a3,
    const uint64_t a4,
    const uint64_t a5,
    const uint64_t a6)
#ifdef SOCT_NEEDS_FATFS
;
#else
{
    resp->status = SOCT_HANDLER_PASS;
}
#endif


inline bool soct_init_from_dtb_sdc()
#ifdef SOCT_NEEDS_FATFS
;
#else
{
    return false;
}
#endif
