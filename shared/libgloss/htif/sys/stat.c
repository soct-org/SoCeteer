#include "syscall.h"

int _stat(const char *pname, struct stat *st) {
    long ret = htif_syscall_2(FESVR_stat, (uintptr_t)pname, (uintptr_t)st);
    return (int)htif_check_ret(ret);
}
