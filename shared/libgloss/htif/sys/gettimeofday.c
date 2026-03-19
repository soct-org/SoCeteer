#include <sys/time.h>

#include "syscall.h"

int _gettimeofday(struct timeval *tv, struct timezone *tz) {
    long ret = htif_syscall_2(FESVR_gettimeofday, (uintptr_t)tv, (uintptr_t)tz);
    return (int)htif_check_ret(ret);
}