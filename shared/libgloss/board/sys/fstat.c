#include "syscall.h"

int _fstat(int fd, struct stat *st) {
    return io_fstat(fd, st);
}
