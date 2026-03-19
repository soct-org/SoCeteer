#include "syscall.h"

// getcwd
char* getcwd(char *buf, size_t size) {
    long ret = htif_syscall_2(FESVR_getcwd, (uintptr_t)buf, size);
    return (char *)htif_check_ptr(ret);
}