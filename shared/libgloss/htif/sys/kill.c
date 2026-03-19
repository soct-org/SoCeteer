#include "syscall.h"

int _kill(int pid, int sig) {
    long ret = htif_syscall_2(FESVR_kill, pid, sig);
    return (int)htif_check_ret(ret);
}