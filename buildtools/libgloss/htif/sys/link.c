#include "syscall.h"

int _link(const char *oldpath, const char *newpath) {
    return htif_syscall_2(FESVR_link, (uintptr_t) oldpath, (uintptr_t) newpath);
}