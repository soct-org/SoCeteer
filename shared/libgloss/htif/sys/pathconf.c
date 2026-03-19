#include "syscall.h"

long pathconf(const char *path, int param) {
    long ret = htif_syscall_2(FESVR_pathconf, (uintptr_t)path, param);
    return htif_check_ret(ret);
}