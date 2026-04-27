#include "syscall-handler.h"

void __attribute__ ((noreturn)) _exit(int code) {
    soct_syscall(SOCT_EXIT, code, 0, 0, 0, 0, 0, 0);
    while (1) {
        __asm__ volatile ("wfi");
    }
}
