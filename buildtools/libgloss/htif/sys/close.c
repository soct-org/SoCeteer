#include "syscall.h"

int _close(int fd) {
    return htif_syscall_1(FESVR_close, fd);
}
