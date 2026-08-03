/* See soct_evdev.h - this file owns <linux/input.h> and its KEY_* namespace. */
#include "soct_evdev.h"

#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static int kbd_fd = -1;

#define BITS_BYTES(count) (((count) + 7) / 8)

static int has_bit(const unsigned char *bits, unsigned int bit) {
    return (bits[bit / 8] >> (bit % 8)) & 1;
}

/* A keyboard: reports EV_KEY and has letter and enter keys - which excludes
 * mice and buttons-only devices, whose EV_KEY range starts at BTN_MISC. */
static int is_keyboard(int fd) {
    unsigned char ev[BITS_BYTES(EV_MAX + 1)] = {0};
    unsigned char keys[BITS_BYTES(KEY_MAX + 1)] = {0};

    if (ioctl(fd, EVIOCGBIT(0, sizeof(ev)), ev) < 0 ||
        ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys)), keys) < 0)
        return 0;
    return has_bit(ev, EV_KEY) && has_bit(keys, KEY_A) && has_bit(keys, KEY_ENTER);
}

int soct_evdev_open(void) {
    char path[32];

    for (int i = 0; i < 32; i++) {
        int fd;

        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0)
            continue;
        if (is_keyboard(fd)) {
            ioctl(fd, EVIOCGRAB, 1);
            kbd_fd = fd;
            return 0;
        }
        close(fd);
    }
    return -1;
}

static int map_code(unsigned short code) {
    /* The keycode rows are contiguous in the same order as on the keycap rows. */
    static const char row_digits[] = "1234567890";
    static const char row_q[] = "qwertyuiop";
    static const char row_a[] = "asdfghjkl";
    static const char row_z[] = "zxcvbnm";

    if (code >= KEY_1 && code <= KEY_0) return row_digits[code - KEY_1];
    if (code >= KEY_Q && code <= KEY_P) return row_q[code - KEY_Q];
    if (code >= KEY_A && code <= KEY_L) return row_a[code - KEY_A];
    if (code >= KEY_Z && code <= KEY_M) return row_z[code - KEY_Z];
    if (code >= KEY_F1 && code <= KEY_F10) return SK_F1 - (code - KEY_F1);
    switch (code) {
    case KEY_F11: return SK_F1 - 10;
    case KEY_F12: return SK_F1 - 11;
    case KEY_UP: return SK_UP;
    case KEY_DOWN: return SK_DOWN;
    case KEY_LEFT: return SK_LEFT;
    case KEY_RIGHT: return SK_RIGHT;
    case KEY_ENTER: case KEY_KPENTER: return SK_ENTER;
    case KEY_ESC: return SK_ESCAPE;
    case KEY_TAB: return SK_TAB;
    case KEY_BACKSPACE: return SK_BACKSPACE;
    case KEY_LEFTCTRL: case KEY_RIGHTCTRL: return SK_CTRL;
    case KEY_LEFTSHIFT: case KEY_RIGHTSHIFT: return SK_SHIFT;
    case KEY_LEFTALT: case KEY_RIGHTALT: return SK_ALT;
    case KEY_SPACE: return ' ';
    case KEY_MINUS: return '-';
    case KEY_EQUAL: return '=';
    case KEY_COMMA: return ',';
    case KEY_DOT: return '.';
    case KEY_SLASH: return '/';
    case KEY_SEMICOLON: return ';';
    case KEY_APOSTROPHE: return '\'';
    case KEY_LEFTBRACE: return '[';
    case KEY_RIGHTBRACE: return ']';
    default: return SK_NONE;
    }
}

int soct_evdev_poll(int *pressed, int *sym) {
    struct input_event ev;

    if (kbd_fd < 0)
        return 0;
    while (read(kbd_fd, &ev, sizeof(ev)) == (ssize_t)sizeof(ev)) {
        int s;

        /* value: 0 release, 1 press, 2 autorepeat (the engine repeats itself) */
        if (ev.type != EV_KEY || ev.value > 1)
            continue;
        s = map_code(ev.code);
        if (s == SK_NONE)
            continue;
        *pressed = ev.value;
        *sym = s;
        return 1;
    }
    return 0;
}

void soct_evdev_close(void) {
    if (kbd_fd >= 0) {
        ioctl(kbd_fd, EVIOCGRAB, 0);
        close(kbd_fd);
        kbd_fd = -1;
    }
}
