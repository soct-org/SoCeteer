#include "syscall.h"

int chdir(const char *path) {
    return htif_syscall_1(FESVR_chdir, (uintptr_t) (path));
}
