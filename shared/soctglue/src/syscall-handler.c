#include <errno.h>

#include "soct/syscall-handler.h"
#include "soct/defaults.h"

/*
 * Handler table: user handlers occupy [0, s_user_count),
 * default handlers occupy [SOCT_N_SYSCALL_HANDLERS - s_default_count, SOCT_N_SYSCALL_HANDLERS).
 * User handlers are always tried first.
 */
static soct_handler_t s_syscall_handlers[SOCT_N_SYSCALL_HANDLERS];
static size_t s_user_count    = 0;
static size_t s_default_count = 0;

bool soct_register_handler(const soct_handler_t handler) {
    if (s_user_count + s_default_count >= SOCT_N_SYSCALL_HANDLERS)
        return false;
    s_syscall_handlers[s_user_count++] = handler;
    return true;
}

bool soct_register_default_handler(const soct_handler_t handler) {
    if (s_user_count + s_default_count >= SOCT_N_SYSCALL_HANDLERS)
        return false;
    /* Default handlers grow from the end of the array downward */
    s_syscall_handlers[SOCT_N_SYSCALL_HANDLERS - 1 - s_default_count] = handler;
    s_default_count++;
    return true;
}


long soct_syscall(
    const uint32_t syscall,
    const sc_arg_t a0,
    const sc_arg_t a1,
    const sc_arg_t a2,
    const sc_arg_t a3,
    const sc_arg_t a4,
    const sc_arg_t a5,
    const sc_arg_t a6) {

    /* Iterate user handlers first, then default handlers (reversed from back of array) */
    for (size_t pass = 0; pass < 2; pass++) {
        size_t count = (pass == 0) ? s_user_count : s_default_count;
        for (size_t i = 0; i < count; i++) {
            const size_t idx = (pass == 0)
                ? i
                : (SOCT_N_SYSCALL_HANDLERS - 1 - i);
            soct_handler_resp_t resp = {.status = SOCT_HANDLER_PASS, .ret = 0};
            s_syscall_handlers[idx].handle(&resp, syscall, a0, a1, a2, a3, a4, a5, a6);
            if (resp.status == SOCT_HANDLER_HANDLED) {
                if (resp.ret < 0)
                    errno = (int) -resp.ret;
                return resp.ret;
            }
        }
    }

    // No handler handled the syscall, return -1 and set errno to ENOSYS
    errno = ENOSYS;
    return -1;
}
