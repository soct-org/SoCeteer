#include "syscall.h"

int mkdir(const char *path, mode_t mode) {
    return htif_syscall_2(FESVR_mkdir, (uintptr_t) path, mode);
}