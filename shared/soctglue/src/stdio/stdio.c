#include <stdarg.h>
#include <stddef.h>
#include "printbuf.h"
#include "compiler.h"

__thread char __printbuf[128] __aligned(64);
__thread size_t __printbuflen = 0;
typedef void (*putc_t)(int, void *);
extern void __vxprintf(putc_t, void *, const char *, va_list);


static void __printf_char(int c, void *arg) {
    int n = __printbuf_putc(c);
    if (likely(n >= 0)) { *(int*)arg += n; }
}


int __wrap_printf(const char *fmt, ...) {
    va_list ap; int len = 0;
    va_start(ap, fmt);
    __vxprintf(__printf_char, &len, fmt, ap);
    va_end(ap);
    return len;
}


int __wrap_puts(const char *s) { return __printbuf_puts(s); }


static void __sprintf_char(int c, void *arg) { *(*(char**)arg)++ = (char)c; }
int __wrap_sprintf(char *str, const char *fmt, ...) {
    va_list ap; char *p = str;
    va_start(ap, fmt); __vxprintf(__sprintf_char, &p, fmt, ap); va_end(ap);
    *p = '\0'; return (int)(p - str);
}


struct snprintf { char *str; char *end; };
static void __snprintf_putc(int c, void *arg) {
    struct snprintf *d = arg;
    if (likely(d->str < d->end)) *(d->str)++ = c;
}


int __wrap_snprintf(char *str, size_t size, const char *fmt, ...) {
    if (size <= 0) return 0;
    va_list ap;
    struct snprintf d = { str, str + size - 1 };
    va_start(ap, fmt); __vxprintf(__snprintf_putc, &d, fmt, ap); va_end(ap);
    *d.str = '\0'; return (int)(d.str - str);
}