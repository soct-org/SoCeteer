#include "syscall.h"

int mkdir(const char *path, mode_t mode) {
    long ret = htif_syscall_2(FESVR_mkdir, (uintptr_t)path, mode);
    return (int)htif_check_ret(ret);
}