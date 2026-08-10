/*
 * gemmini-mnist - the Linux variant of the MNIST-on-Gemmini demo (the bare-metal
 * variant lives in binaries/baremetal/gemmini-mnist; model, sample data and the
 * datatype contract are shared - see shared/mnist/mnist_common.h).
 *
 * Consumes the same on-disk layout the bare-metal variant reads from the SD card:
 * a directory holding image_paths.txt, labels.bin and the raw elem_t image files
 * (produced by shared/mnist/generate.py). The data directory is the required
 * argument - inside soct the SD card is already mounted under /media/<device>.
 *
 * Gemmini's RoCC instructions execute directly from userspace, but only when the
 * kernel enables the extension context for user threads - that is
 * patches/0002-riscv-enable-rocc-extension-context.patch (sstatus.XS); without it
 * every Gemmini instruction traps as an illegal instruction. Gemmini translates its
 * DMA addresses through the core's PTW, so the virtual pointers of this process are
 * fine to hand to the accelerator.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>

#include "mnist_common.h"
#include "model.h"


static elem_t image[1][MNIST_PIXELS];
static elem_t scores[1][MNIST_CLASSES];

static int read_exact(const char *path, void *buf, size_t bytes) {
    FILE *f = fopen(path, "rb");

    if (!f) {
        perror(path);
        return -1;
    }
    if (fread(buf, 1, bytes, f) != bytes) {
        fprintf(stderr, "%s: expected %zu bytes\n", path, bytes);
        fclose(f);
        return -1;
    }
    fclose(f);
    return 0;
}

static uint64_t now_us(void) {
    struct timespec ts;

    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000u + (uint64_t)(ts.tv_nsec / 1000);
}

int main(int argc, char **argv) {
    const char *dir = argv[1];
    char path[512];
    unsigned char labels[4096];
    size_t num = 0, correct = 0;
    uint64_t infer_us = 0;
    FILE *paths_file;
    long labels_len;
    FILE *lf;

    if (argc != 2) {
        fprintf(stderr, "usage: %s <data directory>   (e.g. /media/mmcblk0p1/data)\n", argv[0]);
        return 2;
    }

    /* Gemmini's DMA translates through a hardware page-table walker, and a hardware
     * walker cannot take a page fault: every page the accelerator touches must already
     * be resident. The model weights are demand-paged ELF data the CPU never reads -
     * without this, Gemmini saw unmapped pages, the matmuls contributed nothing, and
     * every image classified as the bias argmax (a constant). */
    if (mlockall(MCL_CURRENT | MCL_FUTURE)) {
        perror("mlockall");
        fprintf(stderr, "continuing without locked pages - predictions may be garbage\n");
    }

    snprintf(path, sizeof path, "%s/labels.bin", dir);
    lf = fopen(path, "rb");
    if (!lf) {
        perror(path);
        fprintf(stderr, "hint: inside soct the SD card is under /media/<device>; the data directory is <mount>/data\n");
        return 1;
    }
    fseek(lf, 0, SEEK_END);
    labels_len = ftell(lf);
    rewind(lf);
    if (labels_len <= 0 || labels_len > (long)sizeof(labels) ||
        fread(labels, 1, (size_t)labels_len, lf) != (size_t)labels_len) {
        fprintf(stderr, "%s: unreadable or larger than %zu labels\n", path, sizeof(labels));
        fclose(lf);
        return 1;
    }
    fclose(lf);

    snprintf(path, sizeof path, "%s/image_paths.txt", dir);
    paths_file = fopen(path, "r");
    if (!paths_file) {
        perror(path);
        return 1;
    }

    /* A clean accelerator TLB before the first virtual-address DMA. */
    gemmini_flush(0);

    char line[256];
    while (num < (size_t)labels_len && fgets(line, sizeof line, paths_file)) {
        size_t len = strlen(line);
        size_t predicted;
        uint64_t t0;

        if (len && line[len - 1] == '\n')
            line[--len] = '\0';
        if (!len)
            continue;

        snprintf(path, sizeof path, "%s/%s", dir, line);
        if (read_exact(path, image, sizeof image)) {
            fclose(paths_file);
            return 1;
        }

        t0 = now_us();
        entry(image, scores);
        infer_us += now_us() - t0;

        predicted = mnist_argmax(scores[0], MNIST_CLASSES);
        if (predicted == labels[num])
            correct++;
        printf("Image %s: expected %u, got %zu\n", path, labels[num], predicted);
        num++;
    }
    fclose(paths_file);

    if (num == 0) {
        fprintf(stderr, "%s: no images listed\n", dir);
        return 1;
    }
    printf("%zu/%zu correct (%.1f%%), %llu us per inference\n", correct, num,
           100.0 * (double)correct / (double)num,
           (unsigned long long)(infer_us / num));
    return correct == num ? 0 : 1;
}
