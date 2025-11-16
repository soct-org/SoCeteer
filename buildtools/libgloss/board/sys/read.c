#include "syscall.h"

ssize_t _read(int file, void *ptr, size_t len) {
    return io_read(file, ptr, len);
}
