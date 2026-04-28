#include <string.h>
#include <unistd.h>

#include "syscall-handler.h"

/**
 * Default trap handler
 * @param epc The program counter at the time of the trap.
 * @param cause The cause of the trap, encoded as a RISC-V mcause value. The low-order bits contain the exception code, and the high-order bit indicates whether it was an interrupt (1) or an exception (0).
 * @param tval The trap value, which may contain additional information about the trap (e.g. faulting address for a page fault).
 * @param regs The values of the 32 general-purpose registers at the time of the trap.
 */
void __attribute__((weak)) handle_trap(uintptr_t epc, uintptr_t cause, uintptr_t tval, uintptr_t regs[32]) {
    (void) epc;
    (void) tval;
    (void) regs;
    /* Extract low-order bits of exception code as positive int */
    int code = cause & ((1UL << ((sizeof(int) << 3) - 1)) - 1);
    /* Encode interrupt as negative value */
    code = ((intptr_t) cause < 0) ? -code : code;
    _exit(code);
    __builtin_unreachable();
}


/**
 * Main function for secondary harts
 *
 * Multi-threaded programs should provide their own implementation.
 */
int __attribute__ ((weak)) __main(int argc, char **argv, char *envp[]) {
    (void) argc;
    (void) argv;
    (void) envp;
    for (;;) {
        __asm__ __volatile__ ("wfi");
    }
}


/// provide __dso_handle for C++ global destructors
void *__dso_handle = 0;
