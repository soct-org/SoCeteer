#include "syscall.h"

int _kill(int pid, int sig) {
    return htif_syscall_2(FESVR_kill, pid, sig);
}