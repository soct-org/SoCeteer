// Specify the posix version we want to support
#define _POSIX_C_SOURCE 200809L

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include "soct/soct_ff.h"
#include "soct-test.h"


static void test_write(soct_test_ctx *ctx) {
    TEST_HDR("write");

    const char *msg = "write to stdout\n";
    ssize_t n = write(STDOUT_FILENO, msg, strlen(msg));
    TEST(ctx, "write stdout returns byte count", n == (ssize_t) strlen(msg));

    const char *errmsg = "write to stderr\n";
    n = write(STDERR_FILENO, errmsg, strlen(errmsg));
    TEST(ctx, "write stderr returns byte count", n == (ssize_t) strlen(errmsg));

    TEST_ERR(ctx, "write 0 bytes is a no-op",
             write(STDOUT_FILENO, nullptr, 0) == 0);

    TEST_ERR(ctx, "write NULL buf with nbyte>0 gives EFAULT",
             write(STDOUT_FILENO, nullptr, 5) == -1 && errno == EFAULT);
}


static void test_read(soct_test_ctx *ctx) {
    TEST_HDR("read (stdin)");

    TEST_ERR(ctx, "read NULL buf with nbyte>0 gives EFAULT",
             read(STDIN_FILENO, nullptr, 5) == -1 && errno == EFAULT);

    TEST_ERR(ctx, "read 0 bytes is a no-op",
             read(STDIN_FILENO, nullptr, 0) == 0);

    printf("  [interactive] enter a line: ");
    fflush(stdout);
    char buf[256] = {0};
    fgets(buf, sizeof(buf), stdin);
    printf("  [interactive] you entered: %s", buf);
    TEST(ctx, "fgets from stdin returned non-empty string", buf[0] != '\0');
}


static void test_isatty(soct_test_ctx *ctx) {
    TEST_HDR("isatty");

    TEST(ctx, "stdin  is a tty", isatty(STDIN_FILENO)  == 1);
    TEST(ctx, "stdout is a tty", isatty(STDOUT_FILENO) == 1);
    TEST(ctx, "stderr is a tty", isatty(STDERR_FILENO) == 1);
    TEST(ctx, "fd 42  is not a tty", isatty(42) == 0);
}


static void test_getpid(soct_test_ctx *ctx) {
    TEST_HDR("getpid");

    TEST(ctx, "getpid returns 1", getpid() == 1);
}


static void test_heap(soct_test_ctx *ctx) {
    TEST_HDR("heap (malloc / sbrk)");

    void *p = malloc(1024);
    TEST(ctx, "malloc 1 KiB succeeds", p != nullptr);
    if (p) {
        memset(p, 0xAB, 1024);
        TEST(ctx, "heap memory is writable and readable",
             static_cast<unsigned char *>(p)[512] == 0xAB);
        free(p);
    }
}


static void test_fstat_std(soct_test_ctx *ctx) {
    TEST_HDR("fstat (standard file descriptors)");

    struct stat st{};

    memset(&st, 0, sizeof(st));
    TEST(ctx, "fstat stdout succeeds", fstat(STDOUT_FILENO, &st) == 0);
    TEST(ctx, "fstat stdout: S_IFCHR set", (st.st_mode & S_IFMT) == S_IFCHR);

    memset(&st, 0, sizeof(st));
    TEST(ctx, "fstat stdin succeeds",  fstat(STDIN_FILENO,  &st) == 0);
    TEST(ctx, "fstat stdin: S_IFCHR set", (st.st_mode & S_IFMT) == S_IFCHR);

    memset(&st, 0, sizeof(st));
    TEST(ctx, "fstat stderr succeeds", fstat(STDERR_FILENO, &st) == 0);

    TEST_ERR(ctx, "fstat with NULL stat buf gives EFAULT",
             fstat(STDOUT_FILENO, nullptr) == -1 && errno == EFAULT);
}


static void test_fopen_errno(soct_test_ctx *ctx) {
    TEST_HDR("fopen / freopen errno on bad arguments");

    TEST_ERR(ctx, "fopen(NULL, \"r\") gives EFAULT",
             fopen(nullptr, "r") == nullptr && errno == EFAULT);

    TEST_ERR(ctx, "fopen(path, NULL) gives EFAULT",
             fopen("/some/path", nullptr) == nullptr && errno == EFAULT);

    TEST_ERR(ctx, "fopen with invalid mode gives EINVAL",
             fopen("/some/path", "z") == nullptr && errno == EINVAL);
}


static void test_file_ops(soct_test_ctx *ctx, const char *dir) {
    char path[256];
    snprintf(path, sizeof(path), "%s/soct-syscall-test.tmp", dir);

    /* ---- open / write / lseek / read / close ---- */
    TEST_HDR("open / write / lseek / read / close");

    int fd = open(path, SOCT_O_WRONLY | SOCT_O_CREAT | SOCT_O_TRUNC, 0644);
    TEST(ctx, "open for write (O_CREAT | O_TRUNC) succeeds", fd >= 0);

    if (fd >= 0) {
        const char *payload = "soctglue syscall test\n";
        ssize_t nw = write(fd, payload, strlen(payload));
        TEST(ctx, "write returns byte count", nw == static_cast<ssize_t>(strlen(payload)));
        TEST(ctx, "close write fd succeeds", close(fd) == 0);

        /* re-open for reading */
        fd = open(path, SOCT_O_RDONLY, 0);
        TEST(ctx, "re-open for read succeeds", fd >= 0);

        if (fd >= 0) {
            char buf[64] = {0};
            ssize_t nr = read(fd, buf, sizeof(buf) - 1);
            TEST(ctx, "read returns byte count", nr == static_cast<ssize_t>(strlen(payload)));
            TEST(ctx, "read data matches written data", strcmp(buf, payload) == 0);

            /* lseek to start and re-read */
            TEST(ctx, "lseek SEEK_SET returns 0", lseek(fd, 0, SEEK_SET) == 0);
            memset(buf, 0, sizeof(buf));
            nr = read(fd, buf, sizeof(buf) - 1);
            TEST(ctx, "re-read after lseek SEEK_SET matches", strcmp(buf, payload) == 0);

            /* lseek to end */
            off_t end = lseek(fd, 0, SEEK_END);
            TEST(ctx, "lseek SEEK_END returns file size",
                 end == static_cast<off_t>(strlen(payload)));

            /* lseek relative */
            const off_t mid = lseek(fd, -(end / 2), SEEK_END);
            TEST(ctx, "lseek SEEK_CUR relative returns valid position",
                 mid >= 0 && mid < end);

            TEST(ctx, "close read fd succeeds", close(fd) == 0);
        }
    }

    /* ---- fstat on file fd ---- */
    TEST_HDR("fstat (file fd)");

    fd = open(path, SOCT_O_RDONLY, 0);
    TEST(ctx, "open for fstat succeeds", fd >= 0);
    if (fd >= 0) {
        struct stat st{};
        memset(&st, 0, sizeof(st));
        TEST(ctx, "fstat on file fd succeeds", fstat(fd, &st) == 0);
        TEST(ctx, "fstat st_size matches written length",
             st.st_size == (off_t) strlen("soctglue syscall test\n"));
        close(fd);
    }

    /* ---- openat ---- */
    TEST_HDR("openat");

    const int dir_fd = open(dir, SOCT_O_DIRECTORY, 0);
    TEST(ctx, "open directory succeeds", dir_fd >= 0);
    if (dir_fd >= 0) {
        const int afd = openat(dir_fd, "soct-openat-test.tmp",
                         SOCT_O_WRONLY | SOCT_O_CREAT | SOCT_O_TRUNC, 0644);
        TEST(ctx, "openat creates file", afd >= 0);
        if (afd >= 0) close(afd);

        TEST_ERR(ctx, "openat NULL path gives EFAULT",
                 openat(dir_fd, nullptr, SOCT_O_RDONLY, 0) == -1 && errno == EFAULT);

        close(dir_fd);
    }

    /* ---- error cases ---- */
    TEST_HDR("error cases (file ops)");

    TEST_ERR(ctx, "open non-existent file returns -1",
             open("/does/not/exist.soct", SOCT_O_RDONLY, 0) == -1);

    char buf8[8];
    TEST_ERR(ctx, "read from bad fd returns -1",
             read(999, buf8, sizeof(buf8)) == -1);

    TEST_ERR(ctx, "write to bad fd returns -1",
             write(999, "x", 1) == -1);

    TEST_ERR(ctx, "close bad fd returns -1",
             close(999) == -1);

    TEST_ERR(ctx, "lseek on bad fd returns -1",
             lseek(999, 0, SEEK_SET) == -1);
}


int main(int argc, char **argv) {
    printf("soctglue syscall test suite\n");

    soct_test_ctx ctx = {0, 0};

    test_write(&ctx);
    test_read(&ctx);
    test_isatty(&ctx);
    test_getpid(&ctx);
    test_heap(&ctx);
    test_fstat_std(&ctx);
    test_fopen_errno(&ctx);

    if (argc >= 2) {
        test_file_ops(&ctx, argv[1]);
    } else {
        printf("\nNo directory argument — skipping file syscall tests.\n");
        printf("Usage: syscall-test <writable-dir>\n");
    }

    printf("\n=== %d passed, %d failed ===\n", ctx.pass, ctx.fail);
    return ctx.fail > 0 ? 1 : 0;
}