#include "syscall.h"

int chdir(const char *path) {
    long ret = htif_syscall_1(FESVR_chdir, (uintptr_t)path);
    return (int)htif_check_ret(ret);
}
