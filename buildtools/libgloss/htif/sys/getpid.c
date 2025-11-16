#include "syscall.h"

int _getpid() {
    return htif_syscall_0(FESVR_getpid);
}