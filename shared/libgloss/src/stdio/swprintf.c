#include <errno.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <wchar.h>

#undef swprintf

int swprintf(wchar_t *restrict ws, size_t n, const wchar_t *restrict format, ...) {
    if (ws == NULL || format == NULL || n == 0) {
        errno = EINVAL;
        return -1;
    }

    size_t fmt_len = wcslen(format);
    char fmt8[fmt_len + 1];

    for (size_t i = 0; i < fmt_len; ++i) {
        wchar_t wc = format[i];
        if ((unsigned long)wc > 0x7FUL) {
            ws[0] = L'\0';
            errno = EILSEQ;
            return -1;
        }
        fmt8[i] = (char)wc;
    }
    fmt8[fmt_len] = '\0';

    char out8[n];

    va_list ap;
    va_start(ap, format);
    int ret = vsnprintf(out8, n, fmt8, ap);
    va_end(ap);

    if (ret < 0) {
        ws[0] = L'\0';
        return -1;
    }

    if ((size_t)ret >= n) {
        size_t copy = n - 1;
        for (size_t i = 0; i < copy; ++i) {
            ws[i] = (unsigned char)out8[i];
        }
        ws[copy] = L'\0';
        errno = EOVERFLOW;
        return -1;
    }

    for (int i = 0; i < ret; ++i) {
        ws[i] = (unsigned char)out8[i];
    }
    ws[ret] = L'\0';
    return ret;
}
