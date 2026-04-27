#include <string.h>
#include <unistd.h>

void __init_tls(void) {
    register char *__thread_self __asm__ ("tp");
    extern char __tdata_start[];
    extern char __tbss_offset[];
    extern char __tdata_size[];
    extern char __tbss_size[];

    memcpy(__thread_self, __tdata_start, (size_t) __tdata_size);
    memset(__thread_self + (size_t) __tbss_offset, 0, (size_t) __tbss_size);
}


void __attribute__((weak)) handle_trap(uintptr_t epc, uintptr_t cause, uintptr_t tval, uintptr_t regs[32]) {
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
    for (;;) {
        __asm__ __volatile__ ("wfi");
    }
}


// provide __dso_handle for C++ global destructors
void *__dso_handle = 0;
