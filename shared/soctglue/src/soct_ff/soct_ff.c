#define _GNU_SOURCE

#include "soct/soct_ff.h"
#include "ff.h"

FATFS s_fs;

static const soct_disk_ops_t *s_disk_ops[SOCT_MAX_DRIVES] = {0};

static const soct_disk_ops_t* s_mount_ops;

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