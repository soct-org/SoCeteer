/*
 * Keyboard input for the doom port, as evdev key press/release events - the tty
 * delivers only characters, and the game needs releases for movement. Kept in its
 * own translation unit: <linux/input.h> and the engine's doomkeys.h both define
 * KEY_* macros with different values, so they must never meet in one file. Events
 * are reported as neutral symbols: positive values are ASCII, negative values the
 * SK_* specials below.
 */
#ifndef SOCT_EVDEV_H
#define SOCT_EVDEV_H

enum {
    SK_NONE = 0,
    SK_UP = -1,
    SK_DOWN = -2,
    SK_LEFT = -3,
    SK_RIGHT = -4,
    SK_ENTER = -5,
    SK_ESCAPE = -6,
    SK_TAB = -7,
    SK_BACKSPACE = -8,
    SK_CTRL = -9,
    SK_SHIFT = -10,
    SK_ALT = -11,
    SK_F1 = -20, /* ..SK_F12 = -31, contiguous */
};

/* Finds the first keyboard among /dev/input/event*, grabs it (keys stop reaching
 * the console shell behind the game). Returns 0, or -1 when none exists. */
int soct_evdev_open(void);

/* Non-blocking: fills pressed (1 press / 0 release) and sym; returns 1 for an
 * event, 0 when the queue is empty or no keyboard is open. */
int soct_evdev_poll(int *pressed, int *sym);

/* Releases the grab; safe without a prior successful open. */
void soct_evdev_close(void);

#endif /* SOCT_EVDEV_H */
