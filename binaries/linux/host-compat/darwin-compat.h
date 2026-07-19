/* Pre-included (via HOSTCFLAGS -include) into the kernel's host tools on macOS:
 * glibc-isms they use that Darwin lacks. Never seen by target code or Linux hosts. */
#ifndef _SOCT_DARWIN_COMPAT_H
#define _SOCT_DARWIN_COMPAT_H

#include <sys/types.h>
#include <unistd.h>
#include <errno.h>

/* Darwin has no O_LARGEFILE (off_t is always 64-bit there). */
#ifndef O_LARGEFILE
#define O_LARGEFILE 0
#endif

/* Linux syscall wrapper used by usr/gen_init_cpio.c; plain read/write fallback.
 * Offsets are honored via pread/pwrite when given (gen_init_cpio passes NULL). */
static inline ssize_t copy_file_range(int fd_in, long long *off_in, int fd_out,
                                      long long *off_out, size_t len,
                                      unsigned int flags) {
    char buf[65536];
    size_t done = 0;
    if (flags != 0) {
        errno = EINVAL;
        return -1;
    }
    while (done < len) {
        size_t chunk = len - done;
        if (chunk > sizeof buf) chunk = sizeof buf;
        ssize_t r = off_in ? pread(fd_in, buf, chunk, (off_t) *off_in)
                           : read(fd_in, buf, chunk);
        if (r < 0) return done ? (ssize_t) done : -1;
        if (r == 0) break;
        ssize_t w = off_out ? pwrite(fd_out, buf, (size_t) r, (off_t) *off_out)
                            : write(fd_out, buf, (size_t) r);
        if (w != r) return done ? (ssize_t) done : -1;
        if (off_in) *off_in += r;
        if (off_out) *off_out += w;
        done += (size_t) r;
    }
    return (ssize_t) done;
}

#endif
