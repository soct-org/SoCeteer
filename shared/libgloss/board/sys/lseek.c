#include <sys/types.h>

#include "syscall.h"

off_t _lseek(int fd, off_t offset, int whence) {
    return io_seek(fd, offset, whence);
}