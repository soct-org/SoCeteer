#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "soct/syscall-handler.h"
#include "soct/soct_ff.h"

int _open(const char *name, int flags, int mode) {
    return soct_syscall(SOCT_OPEN, (uintptr_t) name, flags, mode, 0, 0, 0, 0);
}


int mode_to_soct_flags(const char *mode) {
    bool plus = strchr(mode, '+') != NULL;
    switch (mode[0]) {
        case 'r': return plus
                             ? SOCT_O_RDWR
                             : SOCT_O_RDONLY;
        case 'w': return (plus ? SOCT_O_RDWR : SOCT_O_WRONLY) | SOCT_O_CREAT | SOCT_O_TRUNC;
        case 'a': return (plus ? SOCT_O_RDWR : SOCT_O_WRONLY) | SOCT_O_CREAT | SOCT_O_APPEND;
        default: return -1;
    }
}

FILE *soct_fopen(const char *path, const char *mode) {
    int flags = mode_to_soct_flags(mode);
    if (flags < 0) return NULL;
    int fd = _open(path, flags, 0666);
    if (fd < 0) return NULL;
    return fdopen(fd, mode);
}


FILE *soct_fopen64(const char *path, const char *mode) {
    return soct_fopen(path, mode);
}


FILE *soct_freopen(const char *path, const char *mode, FILE *stream) {
    if (!path) return NULL;
    int flags = mode_to_soct_flags(mode);
    if (flags < 0) {
        fclose(stream);
        return NULL;
    }
    int fd = _open(path, flags, 0666);
    if (fd < 0) {
        fclose(stream);
        return NULL;
    }
    fclose(stream);
    return fdopen(fd, mode);
}


FILE *soct_freopen64(const char *path, const char *mode, FILE *stream) {
    return soct_freopen(path, mode, stream);
}
