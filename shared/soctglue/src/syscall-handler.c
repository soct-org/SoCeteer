#include <errno.h>

#include "syscall-handler.h"
#include "soct/defaults.h"

static soct_handler_t s_syscall_handlers[SOCT_N_SYSCALL_HANDLERS];
static size_t s_syscall_handler_count = 0;

bool soct_register_handler(const soct_handler_t handler) {
    if (s_syscall_handler_count < SOCT_N_SYSCALL_HANDLERS) {
        s_syscall_handlers[s_syscall_handler_count++] = handler;
    } else {
        return -1; // No more slots left
    }
    return 0;
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
    for (size_t i = 0; i < s_syscall_handler_count; i++) {
        soct_handler_resp_t resp = {.status = SOCT_HANDLER_PASS, .ret = 0};
        s_syscall_handlers[i].handle(&resp, syscall, a0, a1, a2, a3, a4, a5, a6);
        switch (resp.status) {
            case SOCT_HANDLER_PASS:
                continue; // Try next handler
            case SOCT_HANDLER_HANDLED:
                if (resp.ret < 0) {
                    errno = (int) -resp.ret;
                }
                return resp.ret;
        }
    }

    // No handler handled the syscall, return -1 and set errno to ENOSYS
    errno = ENOSYS;
    return -1;
}
