#include <errno.h>
#include <string.h>
#include <sys/stat.h>

#include "soct/syscall-handler.h"
#include "soct/soct_ff.h"
#include "soct/syscalls.h"

int _fstat(int fd, struct stat *st) {
    if (!st) {
        errno = EFAULT;
        return -1;
    }

    memset(st, 0, sizeof(*st));

    if (fd == SOCT_STDIN || fd == SOCT_STDOUT || fd == SOCT_STDERR) {
        st->st_mode = S_IFCHR | 0666;
        st->st_nlink = 1;
        st->st_blksize = 64;
        return 0;
    }

    struct soct_stat ss = {0};
    int ret = (int) soct_syscall(SOCT_FSTAT, fd, (uintptr_t) &ss, 0, 0, 0, 0, 0);
    if (ret == 0) {
        st->st_dev = ss.st_dev;
        st->st_ino = ss.st_ino;
        st->st_mode = ss.st_mode;
        st->st_nlink = ss.st_nlink;
        st->st_uid = ss.st_uid;
        st->st_gid = ss.st_gid;
        st->st_rdev = ss.st_rdev;
        st->st_size = ss.st_size;
        st->st_blksize = ss.st_blksize;
        st->st_blocks = ss.st_blocks;
        st->st_atime = ss.st_atime_sec;
        st->st_mtime = ss.st_mtime_sec;
        st->st_ctime = ss.st_ctime_sec;
    }
    return ret;
}
