#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <ff.h>
#include <string.h>


void dump_fatfs_directory(void) {
    DIR dir;
    FILINFO fno;

    FRESULT res = f_opendir(&dir, "/");
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
    FIL fil;
    UINT br;
    static uint8_t buf[512];
    static uint8_t buf2[512];

    // Fill buf with dummy data
    for (int i = 0; i < (int)sizeof(buf); i++) buf[i] = (uint8_t)(i & 0xFF);

    dump_fatfs_directory();

    // Create file on SD card
    FRESULT res = f_open(&fil, "TEST.TXT", FA_WRITE | FA_CREATE_ALWAYS);
    if (res != FR_OK) { printf("Open failed: %d\n", res); return 1; }
    printf("Opened TEST.TXT OK\n");
    res = f_write(&fil, buf, sizeof(buf), &br);
    if (res != FR_OK) { printf("Write failed: %d\n", res); return 1; }
    printf("Written %u bytes\n", br);
    f_close(&fil);

    dump_fatfs_directory();

    // Read back data from file
    res = f_open(&fil, "TEST.TXT", FA_READ);
    if (res != FR_OK) { printf("Open failed: %d\n", res); return 1; }
    res = f_read(&fil, buf2, sizeof(buf2), &br);
    if (res != FR_OK) { printf("Read failed: %d\n", res); return 1; }
    printf("Read back %u bytes\n", br);
    f_close(&fil);

    if (memcmp(buf, buf2, sizeof(buf)) == 0) {
        printf("Data matches!\n");
    } else {
        printf("Data does NOT match!\n");
        // Print both buffers for debugging
        printf("Written data:\n");
        for (size_t i = 0; i < sizeof(buf); i++) {
            printf("%02x ", buf[i]);
            if ((i & 0x0F) == 0x0F) printf("\n");
        }
        printf("\nRead data:\n");
        for (size_t i = 0; i < sizeof(buf2); i++) {
            printf("%02x ", buf2[i]);
            if ((i & 0x0F) == 0x0F) printf("\n");
        }
    }

    // Delete file and show dir
    res = f_unlink("TEST.TXT");
    if (res != FR_OK) { printf("Unlink failed: %d\n", res); return 1; }
    printf("Deleted TEST.TXT\n");

    dump_fatfs_directory();

    return 0;
}