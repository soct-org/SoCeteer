/*
 * fbimg - shows an image on the framebuffer. Decoding is the vendored
 * stb_image (shared/vendor/stb): PNG, JPEG, BMP, GIF, TGA, binary PPM/PGM.
 *
 * Rendering goes through write() (pwrite per row), never mmap: the write path
 * damage-tracks on every design variant - the incoherent fbdev flushes exactly
 * the written lines - so this tool needs no knowledge of the display's coherence
 * story. The image is scaled to fit (nearest neighbor, aspect preserved),
 * centered, and letterboxed black.
 *
 * The framebuffer is shared with the console (fbcon), which keeps painting a
 * blinking cursor and any tty output over whatever else is on screen. So the
 * viewer takes the screen properly: the VT goes to KD_GRAPHICS (fbcon stops
 * rendering entirely - cursor, scrolling, kernel messages), the image is drawn,
 * and fbimg blocks until a key or SIGINT/SIGTERM ends it. Keyboard input keeps
 * flowing to the tty in graphics mode, so both a serial shell and the fb
 * console itself can end it with any key or Ctrl-C. On the way out the VT is
 * set to KD_TEXT - unconditionally, not to the mode found at start: KD_TEXT
 * makes fbcon repaint the console, and it un-wedges a VT that a killed viewer
 * left in graphics mode (run fbimg again, press a key).
 *
 * Decoding large images takes a while on a soft core, so a progress bar runs on
 * stderr while stderr is a terminal. stb_image has no progress hook; the bar is
 * driven by input consumption through the io callbacks, which tracks JPEG
 * decoding closely (it decodes as it reads) but runs ahead for PNG (all input
 * is consumed before the inflate). An interval-timer spinner animates through
 * such post-input phases, and its first tick is what enables drawing at all -
 * decodes shorter than one tick show nothing.
 */
#include <fcntl.h>
#include <linux/fb.h>
#include <linux/kd.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define STBI_NO_HDR
#define STBI_NO_LINEAR
#define STBI_NO_PSD
#define STBI_NO_PIC
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

static volatile sig_atomic_t quit;
static volatile sig_atomic_t g_pct;  /* input consumed, 0..100 */
static volatile sig_atomic_t g_show; /* set by the first timer tick */

static void on_signal(int sig) {
    (void)sig;
    quit = 1;
}

/* All progress drawing happens here, at timer rate: the read path only updates
 * g_pct, and single-writer output cannot tear. Hand-rolled formatting - only
 * async-signal-safe calls are allowed in a handler. */
static void on_tick(int sig) {
    static const char spin[] = "|/-\\";
    static unsigned frame;
    char line[40];
    int pct = g_pct, n = 0;

    (void)sig;
    g_show = 1;
    line[n++] = '\r';
    line[n++] = '[';
    for (int i = 0; i < 20; i++)
        line[n++] = i < pct / 5 ? '#' : '-';
    line[n++] = ']';
    line[n++] = ' ';
    if (pct >= 100)
        line[n++] = '1';
    if (pct >= 10)
        line[n++] = '0' + pct / 10 % 10;
    line[n++] = '0' + pct % 10;
    line[n++] = '%';
    line[n++] = ' ';
    line[n++] = spin[frame++ & 3];
    write(STDERR_FILENO, line, (size_t)n);
}

static double now_s(void) {
    struct timespec ts;

    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

/* stb_image io callbacks over a FILE*, metering how much input was consumed and
 * how long the medium took to deliver it - the load-time report separates a slow
 * card from slow decoding. */
struct progress_src {
    FILE *f;
    long size;
    long consumed;
    double io_s;
};

static int src_read(void *user, char *data, int size) {
    struct progress_src *s = user;
    double t = now_s();
    int n = (int)fread(data, 1, (size_t)size, s->f);

    s->io_s += now_s() - t;
    s->consumed += n;
    if (s->size > 0) {
        long pct = s->consumed * 100 / s->size;

        g_pct = pct > 100 ? 100 : (int)pct;
    }
    return n;
}

static void src_skip(void *user, int n) {
    struct progress_src *s = user;

    fseek(s->f, n, SEEK_CUR);
    s->consumed += n;
}

static int src_eof(void *user) {
    struct progress_src *s = user;

    return feof(s->f) || s->consumed >= s->size;
}

/* The active VT (/dev/tty0); the viewer may be invoked from the serial console,
 * whose own tty has no console mode - the VT is always the one to silence. */
static int open_vt(void) {
    int fd = open("/dev/tty0", O_RDWR | O_NOCTTY);

    if (fd < 0)
        fd = open("/dev/tty1", O_RDWR | O_NOCTTY);
    return fd;
}

/* Block until any key arrives on stdin or a handled signal fires. Echo and
 * line buffering go off; ISIG stays on so Ctrl-C still raises SIGINT. */
static void wait_dismiss(void) {
    struct termios saved, raw;
    int cooked = tcgetattr(STDIN_FILENO, &saved) == 0;

    if (cooked) {
        raw = saved;
        raw.c_lflag &= ~(tcflag_t)(ICANON | ECHO);
        raw.c_cc[VMIN] = 1;
        raw.c_cc[VTIME] = 0;
        tcsetattr(STDIN_FILENO, TCSANOW, &raw);
    }
    while (!quit) {
        char c;
        ssize_t n = read(STDIN_FILENO, &c, 1);

        if (n > 0)
            break;
        if (n == 0) /* stdin at EOF (redirected): only a signal ends the show */
            pause();
    }
    if (cooked)
        tcsetattr(STDIN_FILENO, TCSANOW, &saved);
}

int main(int argc, char **argv) {
    const char *dev = "/dev/fb0";
    const char *path;
    struct fb_var_screeninfo var;
    struct fb_fix_screeninfo fix;
    struct sigaction sa = {0};
    int iw, ih, comp, fd, vt, opt;
    uint8_t *img, *row;
    uint32_t dw, dh, x0, y0;

    while ((opt = getopt(argc, argv, "d:")) != -1) {
        if (opt == 'd')
            dev = optarg;
        else
            goto usage;
    }
    if (optind != argc - 1) {
    usage:
        fprintf(stderr,
                "usage: %s [-d <fbdev>] <image>   (built " __DATE__ " " __TIME__ ")\n"
                "  image: PNG, JPEG, BMP, GIF, TGA, PPM/PGM - held until a key or Ctrl-C\n"
                "  fbdev: framebuffer device (default /dev/fb0)\n",
                argv[0]);
        return 2;
    }
    path = argv[optind];

    struct progress_src src = {.f = fopen(path, "rb")};
    static const stbi_io_callbacks src_cb = {src_read, src_skip, src_eof};

    if (!src.f) {
        perror(path);
        return 1;
    }
    fseek(src.f, 0, SEEK_END);
    src.size = ftell(src.f);
    fseek(src.f, 0, SEEK_SET);

    if (isatty(STDERR_FILENO)) {
        struct sigaction ta = {0};

        ta.sa_handler = on_tick;
        ta.sa_flags = SA_RESTART; /* ticks must not abort the freads they meter */
        sigaction(SIGALRM, &ta, NULL);
        setitimer(ITIMER_REAL,
                  &(struct itimerval){.it_interval = {0, 250000}, .it_value = {0, 250000}},
                  NULL);
    }
    double t_load = now_s();

    img = stbi_load_from_callbacks(&src_cb, &src, &iw, &ih, &comp, 3);
    t_load = now_s() - t_load;
    setitimer(ITIMER_REAL, &(struct itimerval){0}, NULL);
    fclose(src.f);
    if (g_show)
        fprintf(stderr, "\r\033[K"); /* wipe the progress line */
    if (!img) {
        fprintf(stderr, "%s: %s\n", path, stbi_failure_reason());
        return 1;
    }

    fd = open(dev, O_RDWR);
    if (fd < 0) {
        perror(dev);
        return 1;
    }
    if (ioctl(fd, FBIOGET_VSCREENINFO, &var) || ioctl(fd, FBIOGET_FSCREENINFO, &fix)) {
        perror("screen info");
        return 1;
    }
    if (var.bits_per_pixel != 24) {
        fprintf(stderr, "expected a 24bpp framebuffer, got %u\n", var.bits_per_pixel);
        return 1;
    }

    /* Largest destination preserving the aspect ratio. */
    if ((uint64_t)iw * var.yres > (uint64_t)ih * var.xres) {
        dw = var.xres;
        dh = (uint32_t)((uint64_t)ih * var.xres / iw);
    } else {
        dh = var.yres;
        dw = (uint32_t)((uint64_t)iw * var.yres / ih);
    }
    if (dw == 0) dw = 1;
    if (dh == 0) dh = 1;
    x0 = (var.xres - dw) / 2;
    y0 = (var.yres - dh) / 2;

    row = malloc(fix.line_length);
    if (!row) {
        fprintf(stderr, "out of memory\n");
        return 1;
    }

    sa.sa_handler = on_signal; /* no SA_RESTART: reads must return EINTR */
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);

    vt = open_vt();
    if (vt < 0 || ioctl(vt, KDSETMODE, KD_GRAPHICS))
        fprintf(stderr, "warning: no VT to silence - the console may draw over the image\n");

    double t_draw = now_s();

    for (uint32_t y = 0; y < var.yres; y++) {
        memset(row, 0, fix.line_length);
        if (y >= y0 && y < y0 + dh) {
            const uint8_t *src_row = img + (size_t)((uint64_t)(y - y0) * ih / dh) * iw * 3;

            for (uint32_t x = 0; x < dw; x++) {
                const uint8_t *px = src_row + (size_t)((uint64_t)x * iw / dw) * 3;
                uint8_t *dst = row + (size_t)(x0 + x) * 3;

                /* r8g8b8: blue, green, red from the low address up. */
                dst[0] = px[2];
                dst[1] = px[1];
                dst[2] = px[0];
            }
        }
        if (pwrite(fd, row, fix.line_length, (off_t)y * fix.line_length) !=
            (ssize_t)fix.line_length) {
            perror("pwrite");
            if (vt >= 0)
                ioctl(vt, KDSETMODE, KD_TEXT);
            return 1;
        }
    }

    t_draw = now_s() - t_draw;
    printf("%s: %dx%d -> %ux%u at (%u,%u) on %ux%u\n"
           "read %.1fs + decode %.1fs, draw %.1fs - any key or Ctrl-C to leave\n",
           path, iw, ih, dw, dh, x0, y0, var.xres, var.yres,
           src.io_s, t_load - src.io_s, t_draw);
    fflush(stdout);
    wait_dismiss();

    if (vt >= 0) {
        ioctl(vt, KDSETMODE, KD_TEXT); /* fbcon repaints the console over the image */
        close(vt);
    }
    free(row);
    stbi_image_free(img);
    close(fd);
    return 0;
}
