#include "syscall.h"

long pathconf(const char *path, int param) {
    return htif_syscall_2(FESVR_pathconf, (uintptr_t) path, param);
}