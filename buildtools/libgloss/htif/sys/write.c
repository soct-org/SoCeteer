#include "syscall.h"

ssize_t _write(int fd, const void *ptr, size_t len) {
    return htif_syscall_3(FESVR_write, fd, (uintptr_t) ptr, len);
}