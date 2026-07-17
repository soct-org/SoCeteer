/*
 * Freestanding /init for the SoCeteer bring-up initramfs.
 *
 * Built with the repo's vendored bare-metal toolchain (-nostdlib -ffreestanding):
 * newlib cannot target Linux userspace, so this file uses raw Linux syscalls and
 * nothing else. It proves the kernel reached userspace, reports how many harts
 * came online, and then echoes console input back forever - verifying the console
 * in both directions. PID 1 must never return.
 */

#define SYS_read              63
#define SYS_write             64
#define SYS_nanosleep         101
#define SYS_sched_getaffinity 123
#define SYS_uname             160

/* Freestanding code must supply mem* - the compiler emits calls to them for
 * aggregate initialization even with -ffreestanding. */
void *memset(void *dst, int c, unsigned long n) {
    unsigned char *p = dst;
    while (n--) *p++ = (unsigned char) c;
    return dst;
}

static long sysc(long n, long a, long b, long c) {
    register long a7 __asm__("a7") = n;
    register long a0 __asm__("a0") = a;
    register long a1 __asm__("a1") = b;
    register long a2 __asm__("a2") = c;
    __asm__ volatile ("ecall" : "+r"(a0) : "r"(a1), "r"(a2), "r"(a7) : "memory");
    return a0;
}

static unsigned long slen(const char *s) {
    unsigned long n = 0;
    while (s[n]) n++;
    return n;
}

static void wr(const char *s) { sysc(SYS_write, 1, (long) s, (long) slen(s)); }

static void wrn(const char *s, long n) { sysc(SYS_write, 1, (long) s, n); }

static void wru(unsigned long v) {
    char buf[24];
    int i = sizeof buf;
    do {
        buf[--i] = (char) ('0' + v % 10);
        v /= 10;
    } while (v);
    wrn(buf + i, (long) (sizeof buf - i));
}

void _start(void) {
    /* struct new_utsname: six fixed 65-byte fields */
    char uts[6][65];
    wr("\n=== SoCeteer Linux userspace is alive ===\n");
    if (sysc(SYS_uname, (long) uts, 0, 0) == 0) {
        wr(uts[0]); wr(" "); wr(uts[2]); wr(" ("); wr(uts[4]); wr(")\n");
    }

    unsigned long mask[16] = {0};
    long bytes = sysc(SYS_sched_getaffinity, 0, sizeof mask, (long) mask);
    if (bytes > 0) {
        unsigned long cpus = 0;
        for (unsigned long i = 0; i < sizeof mask / sizeof mask[0]; i++) {
            for (unsigned long m = mask[i]; m; m >>= 1) cpus += m & 1;
        }
        wr("CPUs online: ");
        wru(cpus);
        wr("\n");
    }

    wr("Type something; it will be echoed back.\necho> ");
    for (;;) {
        char buf[256];
        long r = sysc(SYS_read, 0, (long) buf, sizeof buf);
        if (r > 0) {
            wr("you said: ");
            wrn(buf, r);
            wr("echo> ");
        } else {
            /* console not readable (yet) - retry rather than exit: PID 1 must live */
            long ts[2] = {0, 100 * 1000 * 1000};
            sysc(SYS_nanosleep, (long) ts, 0, 0);
        }
    }
}
