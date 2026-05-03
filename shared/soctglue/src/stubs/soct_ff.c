#include <stdint.h>

#include "soctglue.h"
#include "soct/soct_ff.h"
#include "soct/syscall-handler.h"


void soct_handle_sdc(
    soct_handler_resp_t *resp,
    const sc_type_t syscall,
    const sc_arg_t a0,
    const sc_arg_t a1,
    const sc_arg_t a2,
    const sc_arg_t a3,
    const sc_arg_t a4,
    const sc_arg_t a5,
    const sc_arg_t a6) {
    (void) syscall;
    (void) a0;
    (void) a1;
    (void) a2;
    (void) a3;
    (void) a4;
    (void) a5;
    (void) a6;
    resp->status = SOCT_HANDLER_PASS;
}

bool soct_init_from_dtb_sdc() {
    soct_add_setup_msg("Soctglue build with ff stubs - no SD-card support");
    return false;
}


FRESULT f_open(FIL *fp, const TCHAR *path, BYTE mode) {
    (void) fp;
    (void) path;
    (void) mode;
    return FR_NO_FILESYSTEM;
}

FRESULT f_close(FIL *fp) {
    (void) fp;
    return FR_NO_FILESYSTEM;
}

FRESULT f_read(FIL *fp, void *buff, UINT btr, UINT *br) {
    (void) fp;
    (void) buff;
    (void) btr;
    (void) br;
    return FR_NO_FILESYSTEM;
}

FRESULT f_write(FIL *fp, const void *buff, UINT btw, UINT *bw) {
    (void) fp;
    (void) buff;
    (void) btw;
    (void) bw;
    return FR_NO_FILESYSTEM;
}

FRESULT f_lseek(FIL *fp, FSIZE_t ofs) {
    (void) fp;
    (void) ofs;
    return FR_NO_FILESYSTEM;
}

FRESULT f_truncate(FIL *fp) {
    (void) fp;
    return FR_NO_FILESYSTEM;
}

FRESULT f_sync(FIL *fp) {
    (void) fp;
    return FR_NO_FILESYSTEM;
}

FRESULT f_opendir(DIR *dp, const TCHAR *path) {
    (void) dp;
    (void) path;
    return FR_NO_FILESYSTEM;
}

FRESULT f_closedir(DIR *dp) {
    (void) dp;
    return FR_NO_FILESYSTEM;
}

FRESULT f_readdir(DIR *dp, FILINFO *fno) {
    (void) dp;
    (void) fno;
    return FR_NO_FILESYSTEM;
}

FRESULT f_findfirst(DIR *dp, FILINFO *fno, const TCHAR *path, const TCHAR *pattern) {
    (void) dp;
    (void) fno;
    (void) path;
    (void) pattern;
    return FR_NO_FILESYSTEM;
}

FRESULT f_findnext(DIR *dp, FILINFO *fno) {
    (void) dp;
    (void) fno;
    return FR_NO_FILESYSTEM;
}

FRESULT f_mkdir(const TCHAR *path) {
    (void) path;
    return FR_NO_FILESYSTEM;
}

FRESULT f_unlink(const TCHAR *path) {
    (void) path;
    return FR_NO_FILESYSTEM;
}

FRESULT f_rename(const TCHAR *path_old, const TCHAR *path_new) {
    (void) path_old;
    (void) path_new;
    return FR_NO_FILESYSTEM;
}

FRESULT f_stat(const TCHAR *path, FILINFO *fno) {
    (void) path;
    (void) fno;
    return FR_NO_FILESYSTEM;
}

FRESULT f_chmod(const TCHAR *path, BYTE attr, BYTE mask) {
    (void) path;
    (void) attr;
    (void) mask;
    return FR_NO_FILESYSTEM;
}

FRESULT f_utime(const TCHAR *path, const FILINFO *fno) {
    (void) path;
    (void) fno;
    return FR_NO_FILESYSTEM;
}

FRESULT f_chdir(const TCHAR *path) {
    (void) path;
    return FR_NO_FILESYSTEM;
}

FRESULT f_chdrive(const TCHAR *path) {
    (void) path;
    return FR_NO_FILESYSTEM;
}

FRESULT f_getcwd(TCHAR *buff, UINT len) {
    (void) buff;
    (void) len;
    return FR_NO_FILESYSTEM;
}

FRESULT f_getfree(const TCHAR *path, DWORD *nclst, FATFS **fatfs) {
    (void) path;
    (void) nclst;
    (void) fatfs;
    return FR_NO_FILESYSTEM;
}

FRESULT f_getlabel(const TCHAR *path, TCHAR *label, DWORD *vsn) {
    (void) path;
    (void) label;
    (void) vsn;
    return FR_NO_FILESYSTEM;
}

FRESULT f_setlabel(const TCHAR *label) {
    (void) label;
    return FR_NO_FILESYSTEM;
}

FRESULT f_forward(FIL *fp, UINT (*func)(const BYTE *, UINT), UINT btf, UINT *bf) {
    (void) fp;
    (void) func;
    (void) btf;
    (void) bf;
    return FR_NO_FILESYSTEM;
}

FRESULT f_expand(FIL *fp, FSIZE_t fsz, BYTE opt) {
    (void) fp;
    (void) fsz;
    (void) opt;
    return FR_NO_FILESYSTEM;
}

FRESULT f_mount(FATFS *fs, const TCHAR *path, BYTE opt) {
    (void) fs;
    (void) path;
    (void) opt;
    return FR_NO_FILESYSTEM;
}

FRESULT f_mkfs(const TCHAR *path, const MKFS_PARM *opt, void *work, UINT len) {
    (void) path;
    (void) opt;
    (void) work;
    (void) len;
    return FR_NO_FILESYSTEM;
}

FRESULT f_fdisk(BYTE pdrv, const LBA_t ptbl[], void *work) {
    (void) pdrv;
    (void) ptbl;
    (void) work;
    return FR_NO_FILESYSTEM;
}

FRESULT f_setcp(WORD cp) {
    (void) cp;
    return FR_NO_FILESYSTEM;
}

int f_putc(TCHAR c, FIL *fp) {
    (void) c;
    (void) fp;
    return -1;
}

int f_puts(const TCHAR *str, FIL *fp) {
    (void) str;
    (void) fp;
    return -1;
}

int f_printf(FIL *fp, const TCHAR *str, ...) {
    (void) fp;
    (void) str;
    return -1;
}

TCHAR *f_gets(TCHAR *buff, int len, FIL *fp) {
    (void) buff;
    (void) len;
    (void) fp;
    return NULL;
}
