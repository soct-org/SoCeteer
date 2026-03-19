#include "syscall.h"

ssize_t _read(int file, void *ptr, size_t len) {
    long ret = htif_syscall_3(FESVR_read, file, (uintptr_t)ptr, len);
    return (ssize_t)htif_check_ret(ret);
}
