/* glibc-style <byteswap.h> for build hosts that lack it (macOS): the kernel's host
 * tools (scripts/mod/modpost.h) use bswap_*. Compiler builtins cover every host. */
#ifndef _BYTESWAP_H
#define _BYTESWAP_H

#define bswap_16(x) __builtin_bswap16(x)
#define bswap_32(x) __builtin_bswap32(x)
#define bswap_64(x) __builtin_bswap64(x)

#endif
