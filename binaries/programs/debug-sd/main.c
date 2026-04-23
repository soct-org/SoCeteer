#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <ff.h>
#include <string.h>


void dump_fatfs_directory(void) {
    DIR dir;
    FILINFO fno;
    FRESULT res;

    res = f_opendir(&dir, "/");
    if (res != FR_OK) {
        printf("f_opendir failed: %d\n", res);
        return;
    }
    printf("Root directory contents:\n");
    for (;;) {
        res = f_readdir(&dir, &fno);
        if (res != FR_OK || fno.fname[0] == 0) break;
        printf("  [%s] size=%lu attr=%02x\n",
                fno.fname, (unsigned long)fno.fsize, fno.fattrib);
    }
    f_closedir(&dir);
}

int main(void) {
    FRESULT res;
    FIL fil;
    UINT br;
    static uint8_t buf[512];

    // Open
    res = f_open(&fil, "BOOT.ELF", FA_READ);
    if (res != FR_OK) { printf("Open failed: %d\n", res); return 1; }
    printf("Opened OK, size=%lu\n", (unsigned long)f_size(&fil));

    // Read first 512 bytes
    res = f_read(&fil, buf, sizeof(buf), &br);
    if (res != FR_OK) { printf("Read failed: %d\n", res); return 1; }
    printf("Read %u bytes\n", br);

    // Check ELF magic
    printf("Magic: %02x %02x %02x %02x\n", buf[0], buf[1], buf[2], buf[3]);
    printf("Class: %02x (2=64bit)\n", buf[4]);
    printf("Data:  %02x (1=LE)\n", buf[5]);

    f_close(&fil);

    dump_fatfs_directory();

    // create file on SD card
    res = f_open(&fil, "TEST.TXT", FA_WRITE | FA_CREATE_ALWAYS);
    if (res != FR_OK) { printf("Open failed: %d\n", res); return 1; }
    printf("Opened OK, size=%lu\n", (unsigned long)f_size(&fil));
    res = f_write(&fil, buf, sizeof(buf), &br);
    if (res != FR_OK) { printf("Write failed: %d\n", res); return 1; }
    printf("Written %u bytes\n", br);
    dump_fatfs_directory();

    return 0;
}