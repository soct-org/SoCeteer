#pragma once
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include "syscall-handler.h"
#include "syscall-uart.h"
#include "soct/defaults.h"

extern __thread char __printbuf[128];
extern __thread size_t __printbuflen;

static inline int to_stdout(const void *buf, size_t nbyte) {
    return (int) write(SOCT_STDOUT, buf, nbyte);
}


static inline int __printbuf_flush(size_t len) {
    __printbuflen = 0;
    return to_stdout(__printbuf, len);
}

static inline int __printbuf_putc(char c) {
    __printbuf[__printbuflen++] = c;
    if ((__printbuflen >= sizeof(__printbuf)) || (c == '\n')) {
        return __printbuf_flush(__printbuflen);
    }
    return 0;
}

static inline int __printbuf_puts(const char *s) {
    char *buf;
    char *end;

    buf = __printbuf + __printbuflen;
    end = __printbuf + sizeof(__printbuf);
    while (buf < end) {
        char c;
        if ((c = *s++) == '\0') {
            *buf++ = '\n';
            return (__printbuf_flush(buf - __printbuf) < 0) ? EOF : 0;
        }
        *buf++ = c;
    }
    if ((__printbuf_flush(sizeof(__printbuf)) < 0) ||
        (to_stdout(s, strlen(s)) < 0) ||
        (to_stdout("\n", 1) < 0)) {
        return EOF;
    }
    return 0;
}
