#include "syscall.h"

int openat(int dirfd, int pname, int flags, int mode) {
    return htif_syscall_4(FESVR_openat, dirfd, pname, flags, mode);
}