#include "syscall.h"

off_t _lseek(int file, off_t ptr, int whence) {
    long ret = htif_syscall_3(FESVR_lseek, file, ptr, whence);
    return (off_t)htif_check_ret(ret);
}
