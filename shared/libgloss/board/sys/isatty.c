#include <unistd.h>

int _isatty(int fd) {
    return (fd == STDOUT_FILENO || fd == STDERR_FILENO);
}
