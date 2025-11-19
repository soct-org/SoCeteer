#include "syscall.h"

int _lseek(int fd, unsigned int offset) {
    return io_seek(fd, offset);
}