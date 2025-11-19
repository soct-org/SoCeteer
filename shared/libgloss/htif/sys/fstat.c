#include "syscall.h"

int _fstat(int fd, struct stat *st) {
    return htif_syscall_2(FESVR_fstat, fd, (uintptr_t) st);
}
