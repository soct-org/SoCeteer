#include "syscall.h"

int _fstat(int fd, struct stat *st) {
    io_fstat(fd, st);
}
