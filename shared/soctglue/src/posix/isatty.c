#include "soct/soct_ff.h"

int _isatty(int fd) {
    return (fd == SOCT_STDIN || fd == SOCT_STDOUT || fd == SOCT_STDERR);
}
