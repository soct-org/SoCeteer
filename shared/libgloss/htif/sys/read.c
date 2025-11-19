#include "syscall.h"

ssize_t _read(int file, void *ptr, size_t len) {
    return htif_syscall_3(FESVR_read, file, (uintptr_t) ptr, len);
}
