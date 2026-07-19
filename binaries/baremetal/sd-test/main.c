#include <stdint.h>
#include <inttypes.h>
#include <stdbool.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>

#include "soct/soct_ff.h"
#include "soct/smoldtb.h"
#include "soct-test.h"

/* =========================================================================
 * DMA mapping test
 *
 * Uses raw disk_read() (bypasses FAT) to verify the SD DMA engine writes
 * to the correct physical address for all memory regions.
 * ========================================================================= */

#define DMA_SECTOR  0u      /* sector used for probing   */
#define DMA_BLKSZ   512u    /* bytes per sector          */
#define DMA_CANARY  0xA5u   /* fill byte before each read */

/* pdrv is volume-order-dependent; resolve at runtime from FF_VOLUME_STRS. */
static int sd_pdrv(void) {
    for (int i = 0; i < FF_VOLUMES; i++)
        if (strcmp(s_vol_str[i], SOCT_SD) == 0) return i;
    return -1;
}

/* Reference sector – filled by test_dma_mapping(), reused by edge test. */
static uint8_t __attribute__((aligned(4))) s_ref[DMA_BLKSZ];
static uint8_t __attribute__((aligned(4))) s_dma_a[DMA_BLKSZ];
static uint8_t __attribute__((aligned(4))) s_dma_b[DMA_BLKSZ];

/* Read one sector into buf and verify against ref.
 * expect_fail=1 inverts the pass condition (used for alignment checks). */
static int dma_probe(const char *label, uint8_t *buf,
                     const uint8_t *ref, int expect_fail) {
    memset(buf, DMA_CANARY, DMA_BLKSZ);
    errno = 0;
    DRESULT dr = disk_read((BYTE) sd_pdrv(), buf, DMA_SECTOR, 1);

    if (expect_fail) {
        if (dr != RES_OK) {
            TEST_PASS(label, "rejected (errno=%d)", errno);
            return 0;
        }
        TEST_FAIL(label, "expected rejection but got RES_OK");
        return 1;
    }
    if (dr != RES_OK) {
        TEST_FAIL(label, "disk_read=%d errno=%d", (int)dr, errno);
        return 1;
    }

    bool all_canary = true;
    for (size_t i = 0; i < DMA_BLKSZ; i++)
        if (buf[i] != DMA_CANARY) {
            all_canary = false;
            break;
        }
    if (all_canary) {
        TEST_FAIL(label, "buffer unchanged – DMA missed addr=0x%" PRIxPTR, (uintptr_t)buf);
        return 1;
    }

    if (ref && memcmp(buf, ref, DMA_BLKSZ) != 0) {
        TEST_FAIL(label, "data mismatch addr=0x%" PRIxPTR, (uintptr_t)buf);
        int shown = 0;
        for (size_t i = 0; i < DMA_BLKSZ && shown < 8; i++) {
            if (buf[i] != ref[i]) {
                printf("         byte[%3zu]: got 0x%02x expected 0x%02x\n", i, buf[i], ref[i]);
                shown++;
            }
        }
        return 1;
    }

    TEST_PASS(label, "addr=0x%" PRIxPTR " data OK", (uintptr_t)buf);
    return 0;
}

static int test_dma_mapping(void) {
    int f = 0;
    TEST_HDR("DMA mapping test");

    int pdrv = sd_pdrv();
    if (pdrv < 0) {
        TEST_SKIP("pdrv", "SOCT_SD not found in FF_VOLUME_STRS");
        return 1;
    }
    printf("  pdrv=%d  sector=%u  block_size=%u\n\n", pdrv, DMA_SECTOR, DMA_BLKSZ);

    memset(s_ref, DMA_CANARY, sizeof(s_ref));
    errno = 0;
    if (disk_read((BYTE) pdrv, s_ref, DMA_SECTOR, 1) != RES_OK) {
        TEST_SKIP("reference read", "disk_read failed (errno=%d) – SD mounted?", errno);
        return 1;
    }
    printf("  reference addr=0x%" PRIxPTR "\n\n", (uintptr_t) s_ref);

    /* Static BSS – two distinct addresses verify the address register updates */
    printf("--- Region: static (BSS) ---\n");
    f += dma_probe("static s_dma_a", s_dma_a, s_ref, 0);
    f += dma_probe("static s_dma_b", s_dma_b, s_ref, 0);
    if (memcmp(s_dma_a, s_dma_b, DMA_BLKSZ) != 0) {
        TEST_FAIL("BSS cross-check", "buffers differ – possible address offset bug");
        f++;
    } else {
        TEST_PASS("BSS cross-check", "s_dma_a == s_dma_b");
    }

    /* Stack */
    printf("\n--- Region: stack ---\n");
    {
        uint8_t raw[DMA_BLKSZ + 4];
        uint8_t *aligned = (uint8_t *) (((uintptr_t) raw + 3) & ~(uintptr_t) 3);
        f += dma_probe("stack (aligned)", aligned, s_ref, 0);
    }

    /* Heap */
    TEST_SECTION("Region: heap");
    {
        uint8_t *raw = malloc(DMA_BLKSZ + 4);
        if (!raw) {
            TEST_SKIP("heap (aligned)", "malloc failed");
        } else {
            uint8_t *aligned = (uint8_t *) (((uintptr_t) raw + 3) & ~(uintptr_t) 3);
            f += dma_probe("heap (aligned)", aligned, s_ref, 0);
            free(raw);
        }
    }

    /* Alignment rejection */
    TEST_SECTION("Alignment check");
    {
        static uint8_t __attribute__((aligned(4))) base[DMA_BLKSZ + 4];
        f += dma_probe("misaligned +1", base + 1, NULL, 1);
        f += dma_probe("misaligned +2", base + 2, NULL, 1);
        f += dma_probe("misaligned +3", base + 3, NULL, 1);
    }

    TEST_RESULT("DMA mapping test", f);
    return f;
}

/* =========================================================================
 * DMA edge-address test
 *
 * RAM extents are read from the DTB "memory" node at runtime.
 * Probes: low edge, just below/above the 4 GB boundary, near top of RAM.
 * ========================================================================= */

static bool dtb_ram_range(uint64_t *base, uint64_t *size) {
    dtb_node *root = dtb_find("/");
    if (!root) return false;
    for (dtb_node *n = dtb_get_child(root); n; n = dtb_get_sibling(n)) {
        dtb_prop *dp = dtb_find_prop(n, "device_type");
        if (!dp) continue;
        const char *s = dtb_read_prop_string(dp, 0);
        if (!s || strcmp(s, "memory") != 0) continue;
        dtb_prop *reg = dtb_find_prop(n, "reg");
        if (!reg) return false;
        dtb_pair layout = {dtb_get_addr_cells_for(n), dtb_get_size_cells_for(n)};
        dtb_pair val = {0, 0};
        if (dtb_read_prop_2(reg, layout, &val) < 1) return false;
        *base = (uint64_t) val.a;
        *size = (uint64_t) val.b;
        return true;
    }
    return false;
}

static int dma_edge_probe(const char *label, uint64_t addr,
                          uint64_t ram_base, uint64_t ram_end) {
    if (addr < ram_base || (addr + DMA_BLKSZ) > ram_end) {
        TEST_SKIP(label, "0x%" PRIx64 " outside RAM", addr);
        return 0;
    }
    if (addr & 3) {
        TEST_SKIP(label, "0x%" PRIx64 " not 4-byte aligned", addr);
        return 0;
    }

    uint8_t *buf = (uint8_t *) (uintptr_t) addr;
    memset(buf, DMA_CANARY, DMA_BLKSZ);
    __asm__ volatile ("fence w,w" ::: "memory");

    errno = 0;
    DRESULT dr = disk_read((BYTE) sd_pdrv(), buf, DMA_SECTOR, 1);
    if (dr != RES_OK) {
        TEST_FAIL(label, "disk_read=%d errno=%d addr=0x%" PRIx64, (int)dr, errno, addr);
        return 1;
    }

    bool all_canary = true;
    for (size_t i = 0; i < DMA_BLKSZ; i++)
        if (buf[i] != DMA_CANARY) {
            all_canary = false;
            break;
        }
    if (all_canary) {
        TEST_FAIL(label, "buffer unchanged – DMA missed 0x%" PRIx64, addr);
        return 1;
    }

    if (memcmp(buf, s_ref, DMA_BLKSZ) != 0) {
        TEST_FAIL(label, "data mismatch at 0x%" PRIx64, addr);
        int shown = 0;
        for (size_t i = 0; i < DMA_BLKSZ && shown < 8; i++) {
            if (buf[i] != s_ref[i]) {
                printf("         byte[%3zu]: got 0x%02x expected 0x%02x\n", i, buf[i], s_ref[i]);
                shown++;
            }
        }
        return 1;
    }

    TEST_PASS(label, "addr=0x%" PRIx64 " data OK", addr);
    return 0;
}

static int test_dma_edges(void) {
    int f = 0;
    TEST_HDR("DMA edge-address test");

    uint64_t ram_base = 0, ram_size = 0;
    if (!dtb_ram_range(&ram_base, &ram_size)) {
        TEST_SKIP("DTB memory node", "not found");
        return 1;
    }
    uint64_t ram_end = ram_base + ram_size;
    printf("  RAM  base=0x%" PRIx64 "  size=0x%" PRIx64 "  end=0x%" PRIx64 "  (%.0f GB)\n\n",
           ram_base, ram_size, ram_end, (double) ram_size / (1024.0 * 1024.0 * 1024.0));

    uint64_t edge_low = ram_base + UINT64_C(0x200000); /* +2 MB above base      */
    uint64_t edge_b4g = UINT64_C(0x100000000) - UINT64_C(0x200); /* last block below 4 GB */
    uint64_t edge_a4g = UINT64_C(0x100000000); /* first block above 4 GB */
    uint64_t edge_high = (ram_end - UINT64_C(0x40000)) & ~UINT64_C(3); /* 256 KB before end */

    printf("  low  edge : 0x%" PRIx64 "  (+2 MB above base)\n", edge_low);
    printf("  below 4GB : 0x%" PRIx64 "  (4 GB - 512 B)\n", edge_b4g);
    printf("  above 4GB : 0x%" PRIx64 "  (4 GB boundary)\n", edge_a4g);
    printf("  high edge : 0x%" PRIx64 "  (256 KB before end)\n\n", edge_high);

    f += dma_edge_probe("low edge", edge_low, ram_base, ram_end);
    f += dma_edge_probe("below 4GB", edge_b4g, ram_base, ram_end);
    f += dma_edge_probe("above 4GB", edge_a4g, ram_base, ram_end);
    f += dma_edge_probe("high edge", edge_high, ram_base, ram_end);

    if (f == 0)
        printf("\n  above-4GB probe passed: DMA address register handles >32-bit addresses.\n");

    TEST_RESULT("DMA edge-address test", f);
    return f;
}

/* =========================================================================
 * Multi-block (CMD18) read test
 *
 * f_mount() and directory walks only ever issue single-block CMD17 reads,
 * but the bootrom loads BOOT.ELF segments with large f_read() calls that
 * become multi-block CMD18 transfers. This test reads N sectors in one
 * CMD18 transfer and verifies each sector against a CMD17 read of the
 * same sector into a known-good BSS buffer.
 * ========================================================================= */

#define MB_MAX_CNT 128u  /* 64 KB — matches the bootrom's 0x10000-byte read chunks */
static uint8_t __attribute__((aligned(4))) s_mb[MB_MAX_CNT * DMA_BLKSZ];

static int mb_verify(const char *label, uint8_t *buf, UINT count) {
    memset(buf, DMA_CANARY, count * DMA_BLKSZ);
    errno = 0;
    DRESULT dr = disk_read((BYTE) sd_pdrv(), buf, DMA_SECTOR, count);
    if (dr != RES_OK) {
        TEST_FAIL(label, "CMD18 disk_read=%d errno=%d addr=0x%" PRIxPTR,
                  (int)dr, errno, (uintptr_t)buf);
        return 1;
    }
    for (UINT s = 0; s < count; s++) {
        memset(s_dma_a, DMA_CANARY, DMA_BLKSZ);
        errno = 0;
        if (disk_read((BYTE) sd_pdrv(), s_dma_a, DMA_SECTOR + s, 1) != RES_OK) {
            TEST_FAIL(label, "CMD17 verify of sector %u failed errno=%d", (unsigned)s, errno);
            return 1;
        }
        if (memcmp(buf + s * DMA_BLKSZ, s_dma_a, DMA_BLKSZ) != 0) {
            TEST_FAIL(label, "sector %u mismatch", (unsigned)s);
            int shown = 0;
            for (size_t i = 0; i < DMA_BLKSZ && shown < 8; i++) {
                if (buf[s * DMA_BLKSZ + i] != s_dma_a[i]) {
                    printf("         sector %u byte[%3zu]: CMD18 0x%02x CMD17 0x%02x\n",
                           (unsigned)s, i, buf[s * DMA_BLKSZ + i], s_dma_a[i]);
                    shown++;
                }
            }
            return 1;
        }
    }
    TEST_PASS(label, "%u sectors OK", (unsigned)count);
    return 0;
}

static int test_multiblock(void) {
    int f = 0;
    TEST_HDR("Multi-block (CMD18) read test");

    if (sd_pdrv() < 0) {
        TEST_SKIP("pdrv", "SOCT_SD not found in FF_VOLUME_STRS");
        return 1;
    }
    printf("  buffer addr=0x%" PRIxPTR "\n\n", (uintptr_t) s_mb);

    f += mb_verify("CMD18 x2   (static)", s_mb, 2);
    f += mb_verify("CMD18 x8   (static)", s_mb, 8);
    f += mb_verify("CMD18 x32  (static)", s_mb, 32);
    f += mb_verify("CMD18 x128 (static)", s_mb, MB_MAX_CNT);

    TEST_RESULT("Multi-block read test", f);
    return f;
}

/* =========================================================================
 * FAT file-system test
 *
 * Creates TEST.TXT, checks it appears in the directory by name, reads it
 * back and verifies content, deletes it, checks it is gone.
 * ========================================================================= */

#define FAT_TEST_FILE  SOCT_SD_PATH "/TEST.TXT"
#define FAT_TEST_NAME  "TEST.TXT"
#define FAT_BUF_LEN    512u

static bool fat_file_exists(const char *name) {
    DIR dir;
    FILINFO fi;
    if (f_opendir(&dir, SOCT_SD_PATH) != FR_OK) return false;
    bool found = false;
    while (f_readdir(&dir, &fi) == FR_OK && fi.fname[0])
        if (strcmp(fi.fname, name) == 0) {
            found = true;
            break;
        }
    f_closedir(&dir);
    return found;
}

static int test_fat_file(void) {
    int f = 0;
    TEST_HDR("FAT file-system test");
    printf("  path: %s\n\n", FAT_TEST_FILE);

    static uint8_t wbuf[FAT_BUF_LEN];
    static uint8_t rbuf[FAT_BUF_LEN];
    for (size_t i = 0; i < FAT_BUF_LEN; i++) wbuf[i] = (uint8_t) (i & 0xFF);

    /* Clean up any stale file from a previous run */
    if (fat_file_exists(FAT_TEST_NAME))
        f_unlink(FAT_TEST_FILE);

    /* Write */
    FILE *fp = fopen(FAT_TEST_FILE, "w+");
    if (!fp) {
        TEST_FAIL("fopen w+", "errno=%d", errno);
        return 1;
    }
    size_t bw = fwrite(wbuf, 1, FAT_BUF_LEN, fp);
    bool werr = ferror(fp);
    fclose(fp);
    if (werr || bw != FAT_BUF_LEN) {
        TEST_FAIL("fwrite", "wrote %zu/%u bytes errno=%d", bw, FAT_BUF_LEN, errno);
        f++;
    } else {
        TEST_PASS("fwrite", "%zu bytes written", bw);
    }

    /* File must appear in directory */
    if (!fat_file_exists(FAT_TEST_NAME)) {
        TEST_FAIL("dir: created", "%s not found after write", FAT_TEST_NAME);
        f++;
    } else {
        TEST_PASS("dir: created", "%s present", FAT_TEST_NAME);
    }

    /* Read back and compare */
    fp = fopen(FAT_TEST_FILE, "r");
    if (!fp) {
        TEST_FAIL("fopen r", "errno=%d", errno);
        f++;
    } else {
        size_t br = fread(rbuf, 1, FAT_BUF_LEN, fp);
        bool rerr = ferror(fp);
        fclose(fp);
        if (rerr || br != FAT_BUF_LEN) {
            TEST_FAIL("fread", "read %zu/%u bytes errno=%d", br, FAT_BUF_LEN, errno);
            f++;
        } else if (memcmp(wbuf, rbuf, FAT_BUF_LEN) != 0) {
            TEST_FAIL("data verify", "content mismatch");
            int shown = 0;
            for (size_t i = 0; i < FAT_BUF_LEN && shown < 8; i++) {
                if (wbuf[i] != rbuf[i]) {
                    printf("         byte[%3zu]: wrote 0x%02x read 0x%02x\n", i, wbuf[i], rbuf[i]);
                    shown++;
                }
            }
            f++;
        } else {
            TEST_PASS("data verify", "%zu bytes match", br);
        }
    }

    /* Delete */
    FRESULT fr = f_unlink(FAT_TEST_FILE);
    if (fr != FR_OK) {
        TEST_FAIL("f_unlink", "FRESULT=%d", (int)fr);
        f++;
    } else {
        TEST_PASS("f_unlink", "deleted");
    }

    /* File must be gone */
    if (fat_file_exists(FAT_TEST_NAME)) {
        TEST_FAIL("dir: deleted", "%s still present after unlink", FAT_TEST_NAME);
        f++;
    } else {
        TEST_PASS("dir: deleted", "%s gone", FAT_TEST_NAME);
    }

    TEST_RESULT("FAT file-system test", f);
    return f;
}

/* =========================================================================
 * Entry point
 * ========================================================================= */

int main(void) {
    int total = 0;
    total += test_dma_mapping();
    total += test_dma_edges();
    total += test_multiblock();
    total += test_fat_file();
    printf("=== TOTAL: %d failure%s ===\n", total, total == 1 ? "" : "s");
    return total == 0 ? 0 : 1;
}
