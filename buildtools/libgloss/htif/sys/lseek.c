#include "syscall.h"

off_t _lseek(int file, off_t ptr, int whence) {
    return htif_syscall_3(FESVR_lseek, file, ptr, whence);
}
