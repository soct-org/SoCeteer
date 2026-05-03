#include "soct/syscall-handler.h"

int _open(const char* name, int flags, int mode) {
    return soct_syscall(SOCT_OPEN, (uintptr_t) name, flags, mode, 0, 0, 0, 0);
}
