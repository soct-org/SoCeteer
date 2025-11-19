#include <stdio.h>
#include "syscall.h"

FILE* fopen(const char* _name, const char* _type) {
    return io_fopen(_name, _type);
}
