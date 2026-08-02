/*
 * fbmode - reports or switches the framebuffer video mode. The shell image's
 * BusyBox carries no fbset applet, so this is the userspace end of soct-dp's mode
 * switching; a stock fbset speaks the same ioctl and works too.
 *
 * The kernel side keeps no mode list: it accepts any complete timing whose pixel
 * clock the design's MMCM can synthesize within the advertised budget - whether a
 * monitor takes a timing is the monitor's call, which no table can predict. The
 * timing policy therefore lives here: a built-in list of well-known standard
 * modes (`fbmode <n>`), and VESA CVT reduced blanking computed for anything else
 * (`fbmode --custom <w> <h> <fps>`; geometries with a standard structure use it -
 * monitors identify modes by their exact timing).
 *
 * Because a wrong guess can leave the monitor dark, a switch must be confirmed:
 * unless -y is given, fbmode waits for 'y' and reverts to the previous mode
 * otherwise - the old timing is read back from the kernel (FBIOGET_VSCREENINFO)
 * before switching, so no state is stored anywhere.
 *
 * All arithmetic is integer on purpose: exact rationals instead of floating
 * point, so the binary carries no FP dependency to trap over.
 */
#include <fcntl.h>
#include <linux/fb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <termios.h>
#include <unistd.h>

static const char *fb_dev = "/dev/fb0";

struct mode {
    const char *name;
    unsigned int w, h, fps; /* fps is nominal; the clock defines the real rate */
    unsigned long long clock_hz;
    unsigned int hfp, hsync, hbp;
    unsigned int vfp, vsync, vbp;
    int hpos, vpos;
};

/* Exact standard structures (CEA-861 / VESA DMT): what monitors expect for these
 * geometries, byte for byte - including the standard clocks (800x600@60 really
 * refreshes at 60.317 Hz; the structure, not the label, is the standard). */
static const struct mode well_known[] = {
    { "640x480@60 (DMT)",     640,  480, 60,  25175000,  16,  96,  48, 10, 2, 33, 0, 0 },
    { "800x600@60 (DMT)",     800,  600, 60,  40000000,  40, 128,  88,  1, 4, 23, 1, 1 },
    { "1024x768@60 (DMT)",   1024,  768, 60,  65000000,  24, 136, 160,  3, 6, 29, 0, 0 },
    { "1280x720@60 (CEA)",   1280,  720, 60,  74250000, 110,  40, 220,  5, 5, 20, 1, 1 },
    { "1280x1024@60 (DMT)",  1280, 1024, 60, 108000000,  48, 112, 248,  1, 3, 38, 1, 1 },
    { "1920x1080@30 (CEA)",  1920, 1080, 30,  74250000,  88,  44, 148,  4, 5, 36, 1, 1 },
    { "1920x1080@60 (CEA)",  1920, 1080, 60, 148500000,  88,  44, 148,  4, 5, 36, 1, 1 },
};
#define NUM_WELL_KNOWN (sizeof(well_known) / sizeof(well_known[0]))

/*
 * VESA CVT 1.2 reduced blanking v1 for an arbitrary active area and refresh:
 * fixed 160-pixel horizontal blank (48/32/80), the standard's 460 us minimum
 * vertical blank, vertical sync width encoding the aspect ratio, hsync positive /
 * vsync negative. The pixel clock keeps the exact htotal * vtotal * fps product
 * (CVT's 0.25 MHz quantization would only skew the refresh - the MMCM synthesizes
 * arbitrary clocks and a DisplayPort sink follows the stream attributes).
 */
static int cvt_rb(unsigned int w, unsigned int h, unsigned int fps, struct mode *m) {
    unsigned int vsync, vbi, min_vbi;

    if (w * 3 == h * 4) vsync = 4;
    else if (w * 9 == h * 16) vsync = 5;
    else if (w * 10 == h * 16) vsync = 6;
    else if (w * 4 == h * 5) vsync = 7;
    else if (w * 9 == h * 15) vsync = 7;
    else vsync = 10;

    /* Lines in 460 us: floor(460us / h_period) + 1 with h_period estimated as
     * (frame_period - 460us) / vactive - as an exact rational:
     * floor(460 * v * fps / (1e6 - 460 * fps)) + 1. */
    if ((unsigned long long)460 * fps >= 1000000) {
        fprintf(stderr, "fbmode: %u fps leaves no room for the vertical blank\n", fps);
        return -1;
    }
    vbi = (unsigned int)(460ULL * h * fps / (1000000ULL - 460ULL * fps)) + 1;
    min_vbi = 3 + vsync + 6;
    if (vbi < min_vbi)
        vbi = min_vbi;

    m->name = "custom (CVT-RB)";
    m->w = w;
    m->h = h;
    m->fps = fps;
    m->hfp = 48;
    m->hsync = 32;
    m->hbp = 80;
    m->vfp = 3;
    m->vsync = vsync;
    m->vbp = vbi - 3 - vsync;
    m->hpos = 1;
    m->vpos = 0;
    m->clock_hz = (unsigned long long)(w + 160) * (h + vbi) * fps;
    return 0;
}

static unsigned int refresh_mhz1000(const struct fb_var_screeninfo *v) {
    unsigned long long htotal = v->xres + v->left_margin + v->right_margin + v->hsync_len;
    unsigned long long vtotal = v->yres + v->upper_margin + v->lower_margin + v->vsync_len;
    unsigned long long dots = (unsigned long long)v->pixclock * htotal * vtotal;

    /* pixclock is the dot period in picoseconds; milli-Hz for display. */
    return dots ? (unsigned int)((1000000000000000ULL + dots / 2) / dots) : 0;
}

static void report(const char *prefix, const struct fb_var_screeninfo *v) {
    unsigned int mhz1000 = refresh_mhz1000(v);

    printf("%s%ux%u@%u.%03u\n", prefix, v->xres, v->yres, mhz1000 / 1000, mhz1000 % 1000);
}

static void mode_to_var(const struct mode *m, struct fb_var_screeninfo *var) {
    var->xres = var->xres_virtual = m->w;
    var->yres = var->yres_virtual = m->h;
    var->xoffset = var->yoffset = 0;
    var->bits_per_pixel = 24;
    var->pixclock = (unsigned int)((2000000000000ULL / m->clock_hz + 1) / 2); /* ps, rounded */
    var->right_margin = m->hfp;
    var->hsync_len = m->hsync;
    var->left_margin = m->hbp;
    var->lower_margin = m->vfp;
    var->vsync_len = m->vsync;
    var->upper_margin = m->vbp;
    var->sync = (m->hpos ? FB_SYNC_HOR_HIGH_ACT : 0) | (m->vpos ? FB_SYNC_VERT_HIGH_ACT : 0);
    var->vmode = FB_VMODE_NONINTERLACED;
    var->activate = FB_ACTIVATE_NOW;
}

static void list_modes(const struct fb_var_screeninfo *cur) {
    size_t i;

    report("current mode: ", cur);
    printf("well-known modes (fbmode <n> to switch):\n");
    for (i = 0; i < NUM_WELL_KNOWN; i++) {
        const struct mode *m = &well_known[i];
        /* Geometry alone is ambiguous (1080p30 vs 1080p60); the dot period breaks
         * the tie - the kernel reports the exactly-achieved clock, which for the
         * standard clocks is the standard value this table carries. */
        int is_cur = m->w == cur->xres && m->h == cur->yres &&
                     cur->pixclock == (2000000000000ULL / m->clock_hz + 1) / 2;

        printf(" %s%zu) %-19s %3llu.%03llu MHz pixel clock\n", is_cur ? "*" : " ",
               i + 1, m->name, m->clock_hz / 1000000, m->clock_hz % 1000000 / 1000);
    }
    printf("anything else: fbmode --custom <width> <height> <fps>\n");
}

/* Waits up to `seconds` for a single 'y' keypress (no Enter needed); everything
 * else - other keys, the timeout, EOF, no terminal - declines. */
static int confirm_keep(int seconds) {
    struct termios saved, raw;
    int keep = 0;

    if (!isatty(STDIN_FILENO)) {
        fprintf(stderr, "fbmode: stdin is not a terminal - cannot confirm, reverting (use -y to keep without asking)\n");
        return 0;
    }
    printf("press 'y' within %d s to keep this mode, any other key reverts: ", seconds);
    fflush(stdout);
    if (tcgetattr(STDIN_FILENO, &saved) == 0) {
        raw = saved;
        raw.c_lflag &= ~(ICANON | ECHO);
        raw.c_cc[VMIN] = 1;
        raw.c_cc[VTIME] = 0;
        tcsetattr(STDIN_FILENO, TCSANOW, &raw);
    }
    {
        struct timeval tv = { .tv_sec = seconds, .tv_usec = 0 };
        fd_set fds;
        char c;

        FD_ZERO(&fds);
        FD_SET(STDIN_FILENO, &fds);
        if (select(STDIN_FILENO + 1, &fds, NULL, NULL, &tv) == 1 &&
            read(STDIN_FILENO, &c, 1) == 1 && (c == 'y' || c == 'Y'))
            keep = 1;
    }
    tcsetattr(STDIN_FILENO, TCSANOW, &saved);
    printf("\n");
    return keep;
}

static int apply(int fd, const struct mode *m, int yes) {
    struct fb_var_screeninfo old, var;

    if (ioctl(fd, FBIOGET_VSCREENINFO, &old) != 0) {
        perror("FBIOGET_VSCREENINFO");
        return 1;
    }
    var = old;
    mode_to_var(m, &var);
    if (ioctl(fd, FBIOPUT_VSCREENINFO, &var) != 0) {
        perror("FBIOPUT_VSCREENINFO");
        fprintf(stderr, "fbmode: %s rejected - the pixel clock (%llu.%03llu MHz) is outside this design's budget (see `dmesg`)\n",
                m->name, m->clock_hz / 1000000, m->clock_hz % 1000000 / 1000);
        return 1;
    }
    if (ioctl(fd, FBIOGET_VSCREENINFO, &var) != 0) {
        perror("FBIOGET_VSCREENINFO");
        return 1;
    }
    report("switched to ", &var);
    if (yes || confirm_keep(10)) {
        printf("kept\n");
        return 0;
    }
    old.activate = FB_ACTIVATE_NOW;
    if (ioctl(fd, FBIOPUT_VSCREENINFO, &old) != 0) {
        perror("FBIOPUT_VSCREENINFO (revert)");
        return 1;
    }
    report("reverted to ", &old);
    return 0;
}

static unsigned long arg_num(const char *arg) {
    char *end;
    unsigned long v = strtoul(arg, &end, 10);

    if (*end || v == 0) {
        fprintf(stderr, "fbmode: '%s' is not a positive number\n", arg);
        exit(2);
    }
    return v;
}

static void usage(void) {
    fprintf(stderr,
            "usage: fbmode                          show the current mode and the well-known list\n"
            "       fbmode [-y] <n>                 switch to well-known mode <n>\n"
            "       fbmode [-y] --custom <w> <h> <fps>   synthesize a timing (CVT reduced blanking;\n"
            "                                       geometries with a standard structure use it)\n"
            "       -y                              keep without asking (default: revert unless 'y'\n"
            "                                       is pressed within 10 s - a wrong mode can leave\n"
            "                                       the monitor dark)\n"
            "       -d <fbdev>                      framebuffer device (default /dev/fb0)\n");
    exit(2);
}

int main(int argc, char **argv) {
    struct fb_var_screeninfo cur;
    struct mode custom;
    const struct mode *m = NULL;
    int yes = 0, fd, i, n = 0;
    char *args[8];

    for (i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-y")) {
            yes = 1;
        } else if (!strcmp(argv[i], "-d")) {
            if (++i >= argc)
                usage();
            fb_dev = argv[i];
        } else if (n < (int)(sizeof(args) / sizeof(args[0]))) {
            args[n++] = argv[i];
        } else {
            usage();
        }
    }

    fd = open(fb_dev, O_RDWR);
    if (fd < 0) {
        perror(fb_dev);
        return 1;
    }
    if (ioctl(fd, FBIOGET_VSCREENINFO, &cur) != 0) {
        perror("FBIOGET_VSCREENINFO");
        return 1;
    }

    if (n == 0) {
        list_modes(&cur);
        return 0;
    }
    if (!strcmp(args[0], "--custom")) {
        size_t k;
        unsigned long w, h, fps;

        if (n != 4)
            usage();
        w = arg_num(args[1]);
        h = arg_num(args[2]);
        fps = arg_num(args[3]);
        /* A geometry the standards define gets its exact standard structure -
         * monitors identify modes by their timing, not by the label. */
        for (k = 0; k < NUM_WELL_KNOWN; k++)
            if (well_known[k].w == w && well_known[k].h == h && well_known[k].fps == fps)
                m = &well_known[k];
        if (!m) {
            if (cvt_rb(w, h, fps, &custom))
                return 2;
            m = &custom;
        }
    } else {
        unsigned long idx;

        if (n != 1)
            usage();
        idx = arg_num(args[0]);
        if (idx > NUM_WELL_KNOWN) {
            fprintf(stderr, "fbmode: no mode %lu (the list has %zu entries)\n",
                    idx, NUM_WELL_KNOWN);
            return 2;
        }
        m = &well_known[idx - 1];
    }
    return apply(fd, m, yes);
}
