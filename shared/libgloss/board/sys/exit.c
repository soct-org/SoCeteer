#include "syscall.h"

void __attribute__ ((noreturn)) _exit(int code) {
  for (;;)
    __asm__ volatile ("wfi");
}