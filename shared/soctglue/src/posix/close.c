#include "soct/syscall-handler.h"

int _close(int fd) {
    return (int) soct_syscall(SOCT_CLOSE, fd, 0, 0, 0, 0, 0, 0);
}
