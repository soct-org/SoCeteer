#include "syscall.h"

// unlink
int _unlink(const char *path) {
    return htif_syscall_1(FESVR_unlink, (uintptr_t) path);
}