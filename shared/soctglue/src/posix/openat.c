#include <errno.h>
#include <stdarg.h>

#include "soct/syscall-handler.h"
#include "soct/soct_ff.h"

int openat(int dirfd, const char *pname, int flags, ...) {
    if (pname == NULL) {
        errno = EFAULT;
        return -1;
    }

    uint64_t mode = 0;
    if (flags & SOCT_O_CREAT) {
        va_list ap;

        va_start(ap, flags);
        mode = (uint64_t)va_arg(ap, int);
        va_end(ap);
    }
    return (int) soct_syscall(SOCT_OPENAT, dirfd, (uintptr_t)pname, flags, mode, 0, 0, 0);
}
