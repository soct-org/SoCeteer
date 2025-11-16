#include "syscall.h"

ssize_t _write(int fd, const void *ptr, size_t len) {
    return io_write(fd, ptr, len);
}