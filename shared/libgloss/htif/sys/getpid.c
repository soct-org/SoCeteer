#include "syscall.h"

int _getpid() {
    long ret = htif_syscall_0(FESVR_getpid);
    return (int)htif_check_ret(ret);
}