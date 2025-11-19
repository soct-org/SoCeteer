#include "syscall.h"

int chmod(const char *path, mode_t mode) {
    return htif_syscall_2(FESVR_chmod, (uintptr_t) path, mode);
}