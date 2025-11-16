#include "syscall.h"

int _close(int fd) {
    return io_close(fd);
}
