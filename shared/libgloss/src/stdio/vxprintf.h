#pragma once

#include <stdarg.h>

typedef void (*putc_t)(int, void *);

extern void __vxprintf(putc_t, void *, const char *, va_list);
