#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

#include "soct/soct_ff.h"

static const char* s_sdc_root = SOCT_SD_PATH;
static const char* s_test_file = SOCT_SD_PATH "/TEST.TXT";


void dump_fatfs_directory(void) {
    DIR dir;
    FILINFO fno;

    FRESULT res = f_opendir(&dir, s_sdc_root);
    if (res != FR_OK) {
        printf("f_opendir failed: %d\n", res);
        return;
    }
    printf("Root directory contents:\n");
    for (;;) {
        res = f_readdir(&dir, &fno);
        if (res != FR_OK || fno.fname[0] == 0) break;
        printf("  [%s] size=%lu attr=%02x\n",
               fno.fname, (unsigned long) fno.fsize, fno.fattrib);
    }
    f_closedir(&dir);
}

int main(void) {
#define BUF_LEN 512
    static uint8_t buf[BUF_LEN];
    static uint8_t buf2[BUF_LEN];

    for (int i = 0; i < (int) sizeof(buf); i++) buf[i] = (uint8_t) (i & 0xFF);
    dump_fatfs_directory();

    FILE *f = fopen(s_test_file, "w+");
    if (!f) {
        perror("fopen");
        return 1;
    }
    printf("Opened file %s for writing\n", s_test_file);

    size_t bw = fwrite(buf, 1, sizeof(buf), f);
    if (ferror(f)) {
        perror("fwrite");
        fclose(f);
        return 1;
    }
    printf("Written %zu bytes\n", bw);
    fclose(f);

    dump_fatfs_directory();

    f = fopen(s_test_file, "r");
    if (!f) {
        perror("fopen");
        return 1;
    }

    size_t br = fread(buf2, 1, sizeof(buf2), f);
    if (ferror(f)) {
        perror("fread");
        fclose(f);
        return 1;
    }
    printf("Read back %zu bytes\n", br);
    fclose(f);

    if (memcmp(buf, buf2, sizeof(buf)) == 0) {
        printf("Data matches!\n");
    } else {
        printf("Data does NOT match!\n");
        for (size_t i = 0; i < BUF_LEN; i++) {
            if (buf[i] != buf2[i])
                printf("  Byte %zu: wrote %02x, read back %02x\n", i, buf[i], buf2[i]);
        }
    }

    FRESULT res = f_unlink(s_test_file);
    if (res != FR_OK) {
        printf("f_unlink(\"%s\") failed: FRESULT %d\n", s_test_file, res);
        return 1;
    }
    printf("Deleted TEST.TXT\n");

    dump_fatfs_directory();

    return 0;
}
