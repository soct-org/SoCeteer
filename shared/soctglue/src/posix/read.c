#include "syscall-handler.h"

ssize_t _read(const int fd, void const *buf, size_t nbyte) {
    return soct_syscall(SOCT_READ, fd, (uintptr_t)buf, nbyte, 0, 0, 0, 0);
}

