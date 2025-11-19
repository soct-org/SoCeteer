#include "common.h"

// SD-Bootrom does:
// a0 = Hart No
// a1, = BOOTROM_DTB_ADDR // Device Tree
.section .text.start, "ax", @progbits
.globl _start
_start:
  li sp, BOOT_MEM_END
  mv s1, a0
  call __parse_dtb
  mv a0, s1
  call __init_uart
  mv a0, s1
  call __init_disk
  li a0, 0 // argc
  li a1, 0 // argv
  call main
_hang:
  wfi
  j _hang