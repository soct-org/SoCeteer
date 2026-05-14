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

static int s_pass = 0;
static int s_fail = 0;

#define SECTION(name) printf("\n[%s]\n", (name))

#define TEST(name, expr)                                                    \
    do {                                                                    \
        int _ok = !!(expr);                                                 \
        if (_ok) {                                                          \
            printf("  PASS  %s\n", (name));                                 \
            s_pass++;                                                       \
        } else {                                                            \
            printf("  FAIL  %s  (errno=%d: %s)\n",                         \
                   (name), errno, strerror(errno));                         \
            s_fail++;                                                       \
        }                                                                   \
    } while (0)

/* Helper: clear errno then evaluate expr, so the TEST errno report is fresh */
#define TEST_ERR(name, expr) do { errno = 0; TEST(name, expr); } while (0)


static void test_write(void) {
    SECTION("write");

    const char *msg = "write to stdout\n";
    ssize_t n = write(STDOUT_FILENO, msg, strlen(msg));
    TEST("write stdout returns byte count", n == (ssize_t) strlen(msg));

    const char *errmsg = "write to stderr\n";
    n = write(STDERR_FILENO, errmsg, strlen(errmsg));
    TEST("write stderr returns byte count", n == (ssize_t) strlen(errmsg));

    TEST_ERR("write 0 bytes is a no-op",
             write(STDOUT_FILENO, NULL, 0) == 0);

    TEST_ERR("write NULL buf with nbyte>0 gives EFAULT",
             write(STDOUT_FILENO, NULL, 5) == -1 && errno == EFAULT);
}


static void test_read(void) {
    SECTION("read (stdin)");

    TEST_ERR("read NULL buf with nbyte>0 gives EFAULT",
             read(STDIN_FILENO, NULL, 5) == -1 && errno == EFAULT);

    TEST_ERR("read 0 bytes is a no-op",
             read(STDIN_FILENO, NULL, 0) == 0);

    printf("  [interactive] enter a line: ");
    fflush(stdout);
    char buf[256] = {0};
    fgets(buf, sizeof(buf), stdin);
    printf("  [interactive] you entered: %s", buf);
    TEST("fgets from stdin returned non-empty string", buf[0] != '\0');
}


static void test_isatty(void) {
    SECTION("isatty");

    TEST("stdin  is a tty", isatty(STDIN_FILENO)  == 1);
    TEST("stdout is a tty", isatty(STDOUT_FILENO) == 1);
    TEST("stderr is a tty", isatty(STDERR_FILENO) == 1);
    TEST("fd 42  is not a tty", isatty(42) == 0);
}


static void test_getpid(void) {
    SECTION("getpid");

    TEST("getpid returns 1", getpid() == 1);
}


static void test_heap(void) {
    SECTION("heap (malloc / sbrk)");

    void *p = malloc(1024);
    TEST("malloc 1 KiB succeeds", p != NULL);
    if (p) {
        memset(p, 0xAB, 1024);
        TEST("heap memory is writable and readable",
             ((unsigned char *) p)[512] == 0xAB);
        free(p);
    }
}


static void test_fstat_std(void) {
    SECTION("fstat (standard file descriptors)");

    struct stat st;

    memset(&st, 0, sizeof(st));
    TEST("fstat stdout succeeds", fstat(STDOUT_FILENO, &st) == 0);
    TEST("fstat stdout: S_IFCHR set", (st.st_mode & S_IFMT) == S_IFCHR);

    memset(&st, 0, sizeof(st));
    TEST("fstat stdin succeeds",  fstat(STDIN_FILENO,  &st) == 0);
    TEST("fstat stdin: S_IFCHR set", (st.st_mode & S_IFMT) == S_IFCHR);

    memset(&st, 0, sizeof(st));
    TEST("fstat stderr succeeds", fstat(STDERR_FILENO, &st) == 0);

    TEST_ERR("fstat with NULL stat buf gives EFAULT",
             fstat(STDOUT_FILENO, NULL) == -1 && errno == EFAULT);
}


static void test_fopen_errno(void) {
    SECTION("fopen / freopen errno on bad arguments");

    TEST_ERR("fopen(NULL, \"r\") gives EFAULT",
             fopen(NULL, "r") == NULL && errno == EFAULT);

    TEST_ERR("fopen(path, NULL) gives EFAULT",
             fopen("/some/path", NULL) == NULL && errno == EFAULT);

    TEST_ERR("fopen with invalid mode gives EINVAL",
             fopen("/some/path", "z") == NULL && errno == EINVAL);
}


static void test_file_ops(const char *dir) {
    char path[256];
    snprintf(path, sizeof(path), "%s/soct-syscall-test.tmp", dir);

    /* ---- open / write / lseek / read / close ---- */
    SECTION("open / write / lseek / read / close");

    int fd = open(path, SOCT_O_WRONLY | SOCT_O_CREAT | SOCT_O_TRUNC, 0644);
    TEST("open for write (O_CREAT | O_TRUNC) succeeds", fd >= 0);

    if (fd >= 0) {
        const char *payload = "soctglue syscall test\n";
        ssize_t nw = write(fd, payload, strlen(payload));
        TEST("write returns byte count", nw == (ssize_t) strlen(payload));
        TEST("close write fd succeeds", close(fd) == 0);

        /* re-open for reading */
        fd = open(path, SOCT_O_RDONLY, 0);
        TEST("re-open for read succeeds", fd >= 0);

        if (fd >= 0) {
            char buf[64] = {0};
            ssize_t nr = read(fd, buf, sizeof(buf) - 1);
            TEST("read returns byte count", nr == (ssize_t) strlen(payload));
            TEST("read data matches written data", strcmp(buf, payload) == 0);

            /* lseek to start and re-read */
            TEST("lseek SEEK_SET returns 0", lseek(fd, 0, SEEK_SET) == 0);
            memset(buf, 0, sizeof(buf));
            nr = read(fd, buf, sizeof(buf) - 1);
            TEST("re-read after lseek SEEK_SET matches", strcmp(buf, payload) == 0);

            /* lseek to end */
            off_t end = lseek(fd, 0, SEEK_END);
            TEST("lseek SEEK_END returns file size",
                 end == (off_t) strlen(payload));

            /* lseek relative */
            off_t mid = lseek(fd, -(end / 2), SEEK_END);
            TEST("lseek SEEK_CUR relative returns valid position",
                 mid >= 0 && mid < end);

            TEST("close read fd succeeds", close(fd) == 0);
        }
    }

    /* ---- fstat on file fd ---- */
    SECTION("fstat (file fd)");

    fd = open(path, SOCT_O_RDONLY, 0);
    TEST("open for fstat succeeds", fd >= 0);
    if (fd >= 0) {
        struct stat st;
        memset(&st, 0, sizeof(st));
        TEST("fstat on file fd succeeds", fstat(fd, &st) == 0);
        TEST("fstat st_size matches written length",
             st.st_size == (off_t) strlen("soctglue syscall test\n"));
        close(fd);
    }

    /* ---- openat ---- */
    SECTION("openat");

    int dir_fd = open(dir, SOCT_O_DIRECTORY, 0);
    TEST("open directory succeeds", dir_fd >= 0);
    if (dir_fd >= 0) {
        int afd = openat(dir_fd, "soct-openat-test.tmp",
                         SOCT_O_WRONLY | SOCT_O_CREAT | SOCT_O_TRUNC, 0644);
        TEST("openat creates file", afd >= 0);
        if (afd >= 0) close(afd);

        TEST_ERR("openat NULL path gives EFAULT",
                 openat(dir_fd, NULL, SOCT_O_RDONLY, 0) == -1 && errno == EFAULT);

        close(dir_fd);
    }

    /* ---- error cases ---- */
    SECTION("error cases (file ops)");

    TEST_ERR("open non-existent file returns -1",
             open("/does/not/exist.soct", SOCT_O_RDONLY, 0) == -1);

    char buf8[8];
    TEST_ERR("read from bad fd returns -1",
             read(999, buf8, sizeof(buf8)) == -1);

    TEST_ERR("write to bad fd returns -1",
             write(999, "x", 1) == -1);

    TEST_ERR("close bad fd returns -1",
             close(999) == -1);

    TEST_ERR("lseek on bad fd returns -1",
             lseek(999, 0, SEEK_SET) == -1);
}


int main(int argc, char **argv) {
    printf("soctglue syscall test suite\n");

    test_write();
    test_read();
    test_isatty();
    test_getpid();
    test_heap();
    test_fstat_std();
    test_fopen_errno();

    if (argc >= 2) {
        test_file_ops(argv[1]);
    } else {
        printf("\nNo directory argument — skipping file syscall tests.\n");
        printf("Usage: syscall-test <writable-dir>\n");
    }

    printf("\n=== %d passed, %d failed ===\n", s_pass, s_fail);
    return s_fail > 0 ? 1 : 0;
}