#include <errno.h>

int _kill(int, int) {
    errno = ENOSYS;
    return -1;
}