#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>

#include "syscall.h"

int openat(int dirfd, const char *pname, int flags, ...) {
    if (pname == NULL) {
        errno = EFAULT;
        return -1;
    }

    mode_t mode = 0;
    if (flags & O_CREAT
#ifdef O_TMPFILE
        || ((flags & O_TMPFILE) == O_TMPFILE)
#endif
    ) {
        va_list ap;

        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }

    long ret = htif_syscall_4(FESVR_openat, dirfd, (uintptr_t)pname, flags, mode);
    return (int)htif_check_ret(ret);
}