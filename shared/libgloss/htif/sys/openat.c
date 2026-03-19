#include "syscall.h"

int openat(int dirfd, const char *pname, int flags, int mode) {
    return htif_syscall_4(FESVR_openat, dirfd, (uintptr_t)pname, flags, mode);
}