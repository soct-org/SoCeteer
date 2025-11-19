#include "syscall.h"

int _gettimeofday(struct timeval *tv, struct timezone *tz) {
    return htif_syscall_2(FESVR_gettimeofday, (uintptr_t) tv, (uintptr_t) tz);
}