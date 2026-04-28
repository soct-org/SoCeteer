#pragma once
#include <stdint.h>

#include "ff.h"
#include "diskio.h"

// RISC-V / Linux open(2) flags (guest-side, architecture-independent Linux ABI)
#define SOCT_O_ACCMODE    0x0003
#define SOCT_O_RDONLY     0x0000
#define SOCT_O_WRONLY     0x0001
#define SOCT_O_RDWR       0x0002
#define SOCT_O_CREAT      0x0040
#define SOCT_O_EXCL       0x0080
#define SOCT_O_NOCTTY     0x0100
#define SOCT_O_TRUNC      0x0200
#define SOCT_O_APPEND     0x0400
#define SOCT_O_NONBLOCK   0x0800
#define SOCT_O_SYNC       0x101000
#define SOCT_O_NOFOLLOW   0x20000
#define SOCT_O_DIRECTORY  0x10000
#define SOCT_O_CLOEXEC    0x80000


#define SOCT_STDIN 0
#define SOCT_STDOUT 1
#define SOCT_STDERR 2


/**
 * Fixed-layout stat structure.
 */
struct soct_stat {
    uint64_t st_dev; /* ID of device containing file */
    uint64_t st_ino; /* inode number */
    uint32_t st_mode; /* protection / file type */
    uint32_t st_nlink; /* number of hard links */
    uint32_t st_uid; /* user ID of owner */
    uint32_t st_gid; /* group ID of owner */
    uint64_t st_rdev; /* device ID (if special file) */
    int64_t st_size; /* total size, in bytes */
    int64_t st_blksize; /* blocksize for file system I/O */
    int64_t st_blocks; /* number of 512-byte blocks allocated */
    int64_t st_atime_sec; /* time of last access (seconds) */
    int64_t st_mtime_sec; /* time of last modification (seconds) */
    int64_t st_ctime_sec; /* time of last status change (seconds) */
}; /* total: 88 bytes */

// Builds a volume path according to FF_STR_VOLUME_ID:
//   0: path unused - errors
//   1: Windows style, e.g. "SD:"
//   2: Linux style,   e.g. "/SD"
#if FF_STR_VOLUME_ID == 2
#define SOCT_ROOT_PATH(vol) "/" vol
#elif FF_STR_VOLUME_ID == 1
#define SOCT_ROOT_PATH(vol) vol ":"
#else
#error "SOCT_ROOT_PATH requires FF_STR_VOLUME_ID to be 1 or 2"
#endif

#define SOCT_RAM "RAM"
#define SOCT_NAND "NAND"
#define SOCT_CF "CF"
#define SOCT_SD "SD"
#define SOCT_SD2 "SD2"
#define SOCT_USB "USB"
#define SOCT_USB2 "USB2"
#define SOCT_USB3 "USB3"

#define SOCT_RAM_PATH  SOCT_ROOT_PATH(SOCT_RAM)
#define SOCT_NAND_PATH SOCT_ROOT_PATH(SOCT_NAND)
#define SOCT_CF_PATH   SOCT_ROOT_PATH(SOCT_CF)
#define SOCT_SD_PATH   SOCT_ROOT_PATH(SOCT_SD)
#define SOCT_SD2_PATH  SOCT_ROOT_PATH(SOCT_SD2)
#define SOCT_USB_PATH  SOCT_ROOT_PATH(SOCT_USB)
#define SOCT_USB2_PATH SOCT_ROOT_PATH(SOCT_USB2)
#define SOCT_USB3_PATH SOCT_ROOT_PATH(SOCT_USB3)

#define SOCT_MAX_DRIVES FF_VOLUMES

typedef DSTATUS (*disk_init_fn_t)(BYTE pdrv);

typedef DSTATUS (*disk_status_fn_t)(BYTE pdrv);

typedef DRESULT (*disk_read_fn_t)(BYTE pdrv, BYTE *buf, LBA_t sector, UINT count);

typedef DRESULT (*disk_write_fn_t)(BYTE pdrv, const BYTE *buf, LBA_t sector, UINT count);

typedef DRESULT (*disk_ioctl_fn_t)(BYTE pdrv, BYTE cmd, void *buf);

typedef struct {
    disk_init_fn_t initialize;
    disk_status_fn_t status;
    disk_read_fn_t read;
    disk_write_fn_t write;
    disk_ioctl_fn_t ioctl;
} soct_disk_ops_t;


/**
 * Set the ops used for mounting subsequent devices.
 * @param ops The disk ops to use for subsequent mounts.
 * The caller retains ownership of the pointer and must ensure it remains valid for the lifetime of the mount.
 */
void soct_set_mount_ops(const soct_disk_ops_t *ops);