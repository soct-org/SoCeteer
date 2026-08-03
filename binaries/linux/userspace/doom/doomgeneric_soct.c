/*
 * doom on the framebuffer console - the soct platform layer under doomgeneric
 * (GPL-2, like the engine it drives; fetched by the build, see CMakeLists.txt).
 *
 * The engine renders its native 320x200 into DG_ScreenBuffer as 32-bit pixels
 * whose in-memory byte order (B,G,R,A) is a prefix of the framebuffer's 24bpp
 * (B,G,R) - the blit is a 4-to-3 byte copy, integer-upscaled to the largest
 * factor the active mode fits and centered. Frames go through write(): the
 * framebuffer driver damage-tracks written lines on every design variant, so
 * this port needs no knowledge of the display's coherence story.
 *
 * Like fbimg, the game takes the screen via KD_GRAPHICS (fbcon stops painting)
 * and restores KD_TEXT on any exit; the keyboard is read as evdev events and
 * grabbed, so gameplay keys stop reaching the shell behind the game.
 *
 * The IWAD (game data) is found on removable media: doom searches the usual
 * file names in the working directory and under /media/<device>/ - copy
 * freedoom1.wad (fetched next to the ELFs) or a real doom WAD onto the card.
 */
#include <dirent.h>
#include <fcntl.h>
#include <linux/fb.h>
#include <linux/kd.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#include "doomgeneric.h"
#include "doomkeys.h"
#include "doomtype.h"
#include "i_video.h" /* CMAP256: the palette (colors[], palette_changed) */
#include "soct_evdev.h"

static int fb_fd = -1;
static int vt_fd = -1;
static struct fb_var_screeninfo var;
static struct fb_fix_screeninfo fix;
static unsigned int scale, x0, y0;
/* The scaled frame, staged as full framebuffer lines (borders pre-blacked), so
 * any contiguous run of game rows is one contiguous pwrite; prev holds the last
 * engine frame to skip rows that did not change (the status bar, most of a
 * menu). One write per frame instead of one per line keeps the deferred flush
 * from scanning out half-written frames. */
static uint8_t *framebuf;
static uint8_t *prev;
/* Palette index -> its scale-repeated 24bpp pixels, rebuilt when the engine
 * changes the palette (damage flashes, item pickups). */
static uint8_t pat[256][8 * 3];
/* Opt-in (-mmap): expand straight into the mapped framebuffer instead of
 * staging + write(). At full-frame change rates this LOSES badly on the
 * incoherent fbdev - its page tracking re-protects every page each flush
 * cycle, so every frame re-faults the whole game area and each fault
 * serializes against the in-flight flush (measured: ~125 ms/frame of draw
 * time, vs ~20 ms through write()). NULL means the write() path is in use. */
static uint8_t *fbmap;
static int want_mmap;

static void restore_console(void) {
    soct_evdev_close();
    if (vt_fd >= 0) {
        ioctl(vt_fd, KDSETMODE, KD_TEXT); /* fbcon repaints the console */
        close(vt_fd);
        vt_fd = -1;
    }
}

static void on_signal(int sig) {
    (void)sig;
    exit(1); /* runs the atexit restore */
}

void DG_Init() {
    struct sigaction sa = {0};

    fb_fd = open("/dev/fb0", O_RDWR);
    if (fb_fd < 0) {
        perror("/dev/fb0");
        exit(1);
    }
    if (ioctl(fb_fd, FBIOGET_VSCREENINFO, &var) || ioctl(fb_fd, FBIOGET_FSCREENINFO, &fix)) {
        perror("screen info");
        exit(1);
    }
    if (var.bits_per_pixel != 24) {
        fprintf(stderr, "doom: expected a 24bpp framebuffer, got %u\n", var.bits_per_pixel);
        exit(1);
    }
    if (var.xres < DOOMGENERIC_RESX || var.yres < DOOMGENERIC_RESY) {
        fprintf(stderr, "doom: %ux%u is smaller than the %ux%u game screen\n",
                var.xres, var.yres, DOOMGENERIC_RESX, DOOMGENERIC_RESY);
        exit(1);
    }
    scale = var.xres / DOOMGENERIC_RESX;
    if (var.yres / DOOMGENERIC_RESY < scale)
        scale = var.yres / DOOMGENERIC_RESY;
    if (scale > 8)
        scale = 8; /* pat[] holds up to 8x; larger monitors get more border */
    x0 = (var.xres - DOOMGENERIC_RESX * scale) / 2;
    y0 = (var.yres - DOOMGENERIC_RESY * scale) / 2;

    framebuf = calloc((size_t)DOOMGENERIC_RESY * scale, fix.line_length);
    prev = calloc((size_t)DOOMGENERIC_RESX * DOOMGENERIC_RESY, 1);
    if (!framebuf || !prev) {
        fprintf(stderr, "doom: out of memory\n");
        exit(1);
    }
    /* Letterbox to black once; the game only repaints its own rectangle. */
    for (uint32_t y = 0; y < var.yres; y += DOOMGENERIC_RESY * scale) {
        size_t lines = var.yres - y;

        if (lines > DOOMGENERIC_RESY * scale)
            lines = DOOMGENERIC_RESY * scale;
        pwrite(fb_fd, framebuf, lines * fix.line_length, (off_t)y * fix.line_length);
    }

    if (want_mmap) {
        fbmap = mmap(NULL, fix.smem_len, PROT_READ | PROT_WRITE, MAP_SHARED, fb_fd, 0);
        if (fbmap == MAP_FAILED)
            fbmap = NULL; /* the write() path below works everywhere */
    }

    sa.sa_handler = on_signal;
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    atexit(restore_console);

    vt_fd = open("/dev/tty0", O_RDWR | O_NOCTTY);
    if (vt_fd < 0)
        vt_fd = open("/dev/tty1", O_RDWR | O_NOCTTY);
    if (vt_fd < 0 || ioctl(vt_fd, KDSETMODE, KD_GRAPHICS))
        fprintf(stderr, "doom: no VT to silence - the console may draw over the game\n");

    if (soct_evdev_open() != 0)
        fprintf(stderr, "doom: no keyboard found (evdev) - demo playback only\n");

    printf("doom: %ux%u at (%u,%u), scale %ux, on %ux%u, %s (built %s %s)\n",
           DOOMGENERIC_RESX * scale, DOOMGENERIC_RESY * scale, x0, y0, scale,
           var.xres, var.yres, fbmap ? "mmap" : "write()", __DATE__, __TIME__);
}

static uint64_t now_us(void) {
    struct timespec ts;

    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000 + (uint64_t)ts.tv_nsec / 1000;
}

void DG_DrawFrame() {
    const uint8_t *src = (const uint8_t *)DG_ScreenBuffer;
    unsigned int first = DOOMGENERIC_RESY, last = 0;
    unsigned int scale3 = scale * 3;
    uint64_t t0 = now_us();
    int force = 0;

    if (palette_changed) {
        for (unsigned int i = 0; i < 256; i++) {
            for (unsigned int r = 0; r < scale; r++) {
                pat[i][r * 3 + 0] = colors[i].b;
                pat[i][r * 3 + 1] = colors[i].g;
                pat[i][r * 3 + 2] = colors[i].r;
            }
        }
        palette_changed = false;
        force = 1; /* same indices, new colors: every row must repaint */
    }

    for (unsigned int y = 0; y < DOOMGENERIC_RESY; y++) {
        const uint8_t *row = src + (size_t)y * DOOMGENERIC_RESX;
        uint8_t *line = fbmap
                ? fbmap + (size_t)(y0 + y * scale) * fix.line_length + x0 * 3
                : framebuf + (size_t)y * scale * fix.line_length + x0 * 3;
        uint8_t *dst = line;

        if (!force &&
            memcmp(row, prev + (size_t)y * DOOMGENERIC_RESX, DOOMGENERIC_RESX) == 0)
            continue;
        memcpy(prev + (size_t)y * DOOMGENERIC_RESX, row, DOOMGENERIC_RESX);
        if (y < first)
            first = y;
        last = y;

        for (unsigned int x = 0; x < DOOMGENERIC_RESX; x++) {
            const uint8_t *p = pat[row[x]];

            for (unsigned int b = 0; b < scale3; b++)
                dst[b] = p[b];
            dst += scale3;
        }
        for (unsigned int r = 1; r < scale; r++)
            memcpy(line + (size_t)r * fix.line_length, line,
                   (size_t)DOOMGENERIC_RESX * scale3);
    }

    if (!fbmap && first <= last)
        pwrite(fb_fd, framebuf + (size_t)first * scale * fix.line_length,
               (size_t)(last - first + 1) * scale * fix.line_length,
               (off_t)(y0 + first * scale) * fix.line_length);

    /* Frame rate and the engine/draw split on stderr once a second - it lands
     * on the serial console, live while the monitor shows the game. */
    {
        static uint64_t draw_us, t_last;
        static uint32_t frames;
        uint64_t now = now_us();

        draw_us += now - t0;
        frames++;
        if (t_last == 0)
            t_last = now;
        if (now - t_last >= 1000000 && frames) {
            fprintf(stderr, "doom: %u fps, draw %u ms/frame\n",
                    (uint32_t)(frames * 1000000ull / (now - t_last)),
                    (uint32_t)(draw_us / frames / 1000));
            frames = 0;
            draw_us = 0;
            t_last = now;
        }
    }
}

void DG_SleepMs(uint32_t ms) {
    struct timespec ts = {.tv_sec = ms / 1000, .tv_nsec = (long)(ms % 1000) * 1000000};

    nanosleep(&ts, NULL);
}

uint32_t DG_GetTicksMs() {
    struct timespec ts;

    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

int DG_GetKey(int *pressed, unsigned char *doomKey) {
    int sym;

    if (!soct_evdev_poll(pressed, &sym))
        return 0;
    if (sym > 0) { /* ASCII; the engine expects lowercase letters */
        *doomKey = (unsigned char)sym;
        return 1;
    }
    switch (sym) {
    case SK_UP: *doomKey = KEY_UPARROW; break;
    case SK_DOWN: *doomKey = KEY_DOWNARROW; break;
    case SK_LEFT: *doomKey = KEY_LEFTARROW; break;
    case SK_RIGHT: *doomKey = KEY_RIGHTARROW; break;
    case SK_ENTER: *doomKey = KEY_ENTER; break;
    case SK_ESCAPE: *doomKey = KEY_ESCAPE; break;
    case SK_TAB: *doomKey = KEY_TAB; break;
    case SK_BACKSPACE: *doomKey = KEY_BACKSPACE; break;
    case SK_CTRL: *doomKey = KEY_FIRE; break;
    case SK_SHIFT: *doomKey = KEY_RSHIFT; break;
    case SK_ALT: *doomKey = KEY_LALT; break;
    default:
        if (sym <= SK_F1 && sym > SK_F1 - 12) {
            *doomKey = KEY_F1 + (SK_F1 - sym);
            break;
        }
        return DG_GetKey(pressed, doomKey); /* unmapped: try the next event */
    }
    return 1;
}

void DG_SetWindowTitle(const char *title) {
    (void)title;
}

/* The usual IWAD names, tried in the working directory and on every mounted
 * medium; first hit wins. A real doom WAD outranks Freedoom when both sit on
 * the same card. */
static const char *const wad_names[] = {
    "doom1.wad", "doom.wad", "doom2.wad", "plutonia.wad", "tnt.wad",
    "freedoom1.wad", "freedoom2.wad",
};

static char wad_path[512];

static const char *find_wad(void) {
    DIR *media;
    struct dirent *e;

    for (size_t i = 0; i < sizeof(wad_names) / sizeof(wad_names[0]); i++) {
        if (access(wad_names[i], R_OK) == 0)
            return wad_names[i];
    }
    media = opendir("/media");
    if (media) {
        while ((e = readdir(media)) != NULL) {
            if (e->d_name[0] == '.')
                continue;
            for (size_t i = 0; i < sizeof(wad_names) / sizeof(wad_names[0]); i++) {
                snprintf(wad_path, sizeof(wad_path), "/media/%s/%s", e->d_name, wad_names[i]);
                if (access(wad_path, R_OK) == 0) {
                    closedir(media);
                    return wad_path;
                }
            }
        }
        closedir(media);
    }
    return NULL;
}

int main(int argc, char **argv) {
    char **args = argv;
    int nargs = argc;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-iwad") == 0)
            nargs = 0; /* explicit WAD given: pass through untouched */
        if (strcmp(argv[i], "-mmap") == 0)
            want_mmap = 1;
    }
    if (nargs) {
        const char *wad = find_wad();

        if (!wad) {
            fprintf(stderr,
                    "doom: no IWAD found - put freedoom1.wad (or doom1.wad etc.) on the\n"
                    "      SD card / USB stick and enter or mount it (soct), or pass -iwad <path>\n");
            return 1;
        }
        args = malloc((size_t)(argc + 3) * sizeof(char *));
        if (!args)
            return 1;
        memcpy(args, argv, (size_t)argc * sizeof(char *));
        args[argc] = "-iwad";
        args[argc + 1] = (char *)wad;
        args[argc + 2] = NULL;
        nargs = argc + 2;
    } else {
        nargs = argc;
    }

    doomgeneric_Create(nargs, args);
    for (;;)
        doomgeneric_Tick();
    return 0;
}
