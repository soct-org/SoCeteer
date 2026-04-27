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
    const uint64_t arg0,
    const uint64_t arg1,
    const uint64_t arg2,
    const uint64_t arg3,
    const uint64_t arg4,
    const uint64_t arg5,
    const uint64_t arg6) {
    for (size_t i = 0; i < s_syscall_handler_count; i++) {
        soct_handler_resp_t resp = {.status = SOCT_HANDLER_PASS, .ret = 0};
        s_syscall_handlers[i].handle(&resp, syscall, arg0, arg1, arg2, arg3, arg4, arg5, arg6);
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
