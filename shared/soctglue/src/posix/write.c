#include "soct/syscall-handler.h"

ssize_t _write(const int fd, const void *buf, const size_t nbyte) {
    return soct_syscall(SOCT_WRITE, fd, (uintptr_t)buf, nbyte, 0, 0, 0, 0);
}