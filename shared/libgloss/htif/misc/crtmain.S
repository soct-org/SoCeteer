#include "asm.h"

    .section .text.init
    .global _start_main
    .type _start_main, @function
_start_main:
    BARRIER_PASS __boot_sync
    ENTER main

    .global _start_secondary
    .type _start_secondary, @function
_start_secondary:
    /* Synchronize secondary harts */
    BARRIER_WAIT __boot_sync
    ENTER __main


    .section .rodata
    .align 3
argv:
    .dc.a name
    .dc.a 0
name:
    .asciz "NOT COMPILED WITH ARGV SUPPORT"

    .bss
__boot_sync:
    .word 0
