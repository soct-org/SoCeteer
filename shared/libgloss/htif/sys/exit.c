#include "syscall.h"

void __attribute__ ((noreturn)) _exit(int code) {
    for (;;)
        htif_syscall_3(FESVR_exit, code, 0, 0);
}
