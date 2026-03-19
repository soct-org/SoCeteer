#include "syscall.h"

// unlink
int _unlink(const char *path) {
    long ret = htif_syscall_1(FESVR_unlink, (uintptr_t)path);
    return (int)htif_check_ret(ret);
}