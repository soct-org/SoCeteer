#include <stdarg.h>
#include "vxprintf.h"

struct sprintf {
    char *str;
};

static void __sprintf_char(int c, void *arg) {
    struct sprintf *data = arg;
    *(data->str)++ = (char)c;
}

int __wrap_sprintf(char *str, const char *fmt, ...) {
    va_list ap;
    struct sprintf data = {
        .str = str,
    };

    va_start(ap, fmt);
    __vxprintf(__sprintf_char, &data, fmt, ap);
    va_end(ap);

    *(data.str) = '\0';
    return (int)(data.str - str);
}
