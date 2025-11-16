#include "syscall.h"

int _stat(char* pname, struct stat *st) {
    return htif_syscall_2(FESVR_stat, (uintptr_t) pname, (uintptr_t) st);
}
