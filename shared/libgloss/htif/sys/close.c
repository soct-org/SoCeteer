#include "syscall.h"

int _close(int fd) {
    long ret = htif_syscall_1(FESVR_close, fd);
    return (int)htif_check_ret(ret);
}
