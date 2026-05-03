#include <stdio.h>

#include "soct/syscall-handler.h"
#include "soct/syscalls.h"

off_t _lseek(int fd, off_t ptr, int whence) {
    return soct_syscall(SOCT_LSEEK, fd, ptr, whence, 0, 0, 0, 0);
}
