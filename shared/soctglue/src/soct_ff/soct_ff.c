#define _GNU_SOURCE

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>

#include "ff.h"
#include "soct/soct_ff.h"
#include "default-ff.h"

FATFS s_fs[FF_VOLUMES];

static const soct_disk_ops_t *s_disk_ops[SOCT_MAX_DRIVES] = {0};
static const soct_disk_ops_t *s_mount_ops;

void soct_set_mount_ops(const soct_disk_ops_t *ops) {
    s_mount_ops = ops;
}

DSTATUS disk_initialize(BYTE pdrv) {
    if (pdrv >= SOCT_MAX_DRIVES)
        return STA_NOINIT;
    s_disk_ops[pdrv] = s_mount_ops;
    return s_disk_ops[pdrv]->initialize(pdrv);
}

DSTATUS disk_status(BYTE pdrv) {
    if (pdrv >= SOCT_MAX_DRIVES || !s_disk_ops[pdrv] || !s_disk_ops[pdrv]->status)
        return STA_NOINIT;
    return s_disk_ops[pdrv]->status(pdrv);
}

DRESULT disk_read(BYTE pdrv, BYTE *buff, LBA_t sector, UINT count) {
    if (pdrv >= SOCT_MAX_DRIVES || !s_disk_ops[pdrv] || !s_disk_ops[pdrv]->read)
        return RES_NOTRDY;
    return s_disk_ops[pdrv]->read(pdrv, buff, sector, count);
}

DRESULT disk_write(BYTE pdrv, const BYTE *buff, LBA_t sector, UINT count) {
    if (pdrv >= SOCT_MAX_DRIVES || !s_disk_ops[pdrv] || !s_disk_ops[pdrv]->write)
        return RES_NOTRDY;
    return s_disk_ops[pdrv]->write(pdrv, buff, sector, count);
}

DRESULT disk_ioctl(BYTE pdrv, BYTE cmd, void *buff) {
    if (pdrv >= SOCT_MAX_DRIVES || !s_disk_ops[pdrv] || !s_disk_ops[pdrv]->ioctl)
        return RES_NOTRDY;
    return s_disk_ops[pdrv]->ioctl(pdrv, cmd, buff);
}

static ssize_t ff_cookie_read(void *cookie, char *buf, size_t size) {
    UINT br;
    if (f_read((FIL *) cookie, buf, (UINT) size, &br) != FR_OK) {
        errno = EIO;
        return -1;
    }
    return (ssize_t) br;
}

static ssize_t ff_cookie_write(void *cookie, const char *buf, size_t size) {
    UINT bw;
    if (f_write((FIL *) cookie, buf, (UINT) size, &bw) != FR_OK) {
        errno = EIO;
        return -1;
    }
    return (ssize_t) bw;
}

static int ff_cookie_seek(void *cookie, off_t *offset, int whence) {
    FIL *fp = (FIL *) cookie;
    FSIZE_t new_pos;
    switch (whence) {
        case SEEK_SET: new_pos = (FSIZE_t) *offset;
            break;
        case SEEK_CUR: new_pos = f_tell(fp) + (FSIZE_t) *offset;
            break;
        case SEEK_END: new_pos = f_size(fp) + (FSIZE_t) *offset;
            break;
        default: errno = EINVAL; return -1;
    }
    if (f_lseek(fp, new_pos) != FR_OK) {
        errno = EIO;
        return -1;
    }
    *offset = (off_t) f_tell(fp);
    return 0;
}

static int ff_cookie_close(void *cookie) {
    FIL *fp = (FIL *) cookie;
    FRESULT r = f_close(fp);
    free(fp);
    if (r != FR_OK) {
        errno = EIO;
        return -1;
    }
    return 0;
}

static BYTE parse_ff_mode(const char *mode) {
    bool plus = strchr(mode, '+') != NULL;
    switch (mode[0]) {
        case 'r': return plus ? FA_READ | FA_WRITE : FA_READ;
        case 'w': return (plus ? FA_READ | FA_WRITE : FA_WRITE) | FA_CREATE_ALWAYS;
        case 'a': return (plus ? FA_READ | FA_WRITE : FA_WRITE) | FA_OPEN_APPEND;
        default: return 0;
    }
}

static bool is_ff_path(const char *path) {
    for (int i = 0; i < FF_VOLUMES; i++) {
        // e.g. "/SD/" or "/SD" at end of string
        size_t vlen = strlen(s_vol_str[i]);
        // +1 for the leading '/'
        if (strncmp(path + 1, s_vol_str[i], vlen) == 0) {
            char next = path[1 + vlen];
            if (next == '/' || next == '\0')
                return true;
        }
    }
    return false;
}

FILE *__wrap_fopen(const char *path, const char *mode) {
    if (!path || !mode) {
        errno = EFAULT;
        return NULL;
    }

    BYTE flags = parse_ff_mode(mode);
    if (!flags) {
        errno = EINVAL;
        return NULL;
    }

    FIL *fp = malloc(sizeof(FIL));
    if (!fp) {
        errno = ENOMEM;
        return NULL;
    }

    if (f_open(fp, path, flags) != FR_OK) {
        free(fp);
        errno = EIO;
        return NULL;
    }

    static const cookie_io_functions_t ff_io = {
        .read = ff_cookie_read,
        .write = ff_cookie_write,
        .seek = ff_cookie_seek,
        .close = ff_cookie_close,
    };

    FILE *f = fopencookie(fp, mode, ff_io);
    if (!f) {
        /* errno already set by fopencookie */
        f_close(fp);
        free(fp);
        return NULL;
    }
    return f;
}

FILE *__wrap_fopen64(const char *path, const char *mode) {
    return __wrap_fopen(path, mode);
}

FILE *__wrap_freopen(const char *path, const char *mode, FILE *stream) {
    if (!path || !mode) {
        errno = EFAULT;
        return NULL;
    }
    fclose(stream);
    return __wrap_fopen(path, mode);
}

FILE *__wrap_freopen64(const char *path, const char *mode, FILE *stream) {
    if (!path || !mode) {
        errno = EFAULT;
        return NULL;
    }
    fclose(stream);
    return __wrap_fopen(path, mode);
}

void init_soct_ff(void) {
    soct_init_from_dtb_sdc();
}