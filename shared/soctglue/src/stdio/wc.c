#include <errno.h>
#include <stdio.h>
#include <stdarg.h>
#include <stddef.h>
#include <wchar.h>

#undef getwc
#undef putwc
#undef ungetwc
#undef swprintf

wint_t getwc(FILE *stream) {
    int c = fgetc(stream);
    return c == EOF ? WEOF : (wint_t)(unsigned char)c;
}

wint_t putwc(wchar_t wc, FILE *stream) {
    if ((unsigned long)wc > 0xFFUL) { errno = EILSEQ; return WEOF; }
    int rc = fputc((unsigned char)wc, stream);
    return rc == EOF ? WEOF : (wint_t)(unsigned char)wc;
}

wint_t ungetwc(wint_t wc, FILE *stream) {
    if (wc == WEOF) return WEOF;
    if ((unsigned long)wc > 0xFFUL) { errno = EILSEQ; return WEOF; }
    int rc = ungetc((unsigned char)wc, stream);
    return rc == EOF ? WEOF : (wint_t)(unsigned char)wc;
}

int swprintf(wchar_t *restrict ws, size_t n, const wchar_t *restrict format, ...) {
    if (ws == NULL || format == NULL || n == 0) { errno = EINVAL; return -1; }

    size_t fmt_len = wcslen(format);
    char fmt8[fmt_len + 1];
    for (size_t i = 0; i < fmt_len; ++i) {
        if ((unsigned long)format[i] > 0x7FUL) { ws[0] = L'\0'; errno = EILSEQ; return -1; }
        fmt8[i] = (char)format[i];
    }
    fmt8[fmt_len] = '\0';

    char out8[n];
    va_list ap;
    va_start(ap, format);
    int ret = vsnprintf(out8, n, fmt8, ap);
    va_end(ap);

    if (ret < 0) { ws[0] = L'\0'; return -1; }
    size_t copy = (size_t)ret >= n ? n - 1 : (size_t)ret;
    for (size_t i = 0; i < copy; ++i) ws[i] = (unsigned char)out8[i];
    ws[copy] = L'\0';
    if ((size_t)ret >= n) { errno = EOVERFLOW; return -1; }
    return ret;
}