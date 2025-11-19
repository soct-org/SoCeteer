#include <errno.h>
#include <stddef.h>
int _getentropy(void *buffer, size_t length) {
    errno = ENOSYS;
    return -1;
}