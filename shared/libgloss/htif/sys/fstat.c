#include "syscall.h"
#include <errno.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

int _fstat(int fd, struct stat *st) {
    if (!st) {
        errno = EFAULT;
        return -1;
    }

    memset(st, 0, sizeof(*st));

    if (fd == STDIN_FILENO || fd == STDOUT_FILENO || fd == STDERR_FILENO) {
        st->st_mode = S_IFCHR | 0666;
        st->st_nlink = 1;
        st->st_blksize = 64;   // or 1024; either is fine for "console-ish"
        return 0;
    }

    long ret = htif_syscall_2(FESVR_fstat, fd, (uintptr_t)st);
    if (ret < 0) {
        errno = (int)-ret;
        return -1;
    }

    return 0;
}
