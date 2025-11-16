#define _POSIX_C_SOURCE 200809L

#include <stdarg.h>
#include <string.h>
#include <limits.h>

typedef void (*putc_t)(int, void *);

static inline unsigned long long getuint(va_list *ap, int lflag) {
    if (lflag >= 2)
        return va_arg(*ap,
    unsigned long long);
    else if (lflag)
        return va_arg(*ap,
    unsigned long);
    else
    return va_arg(*ap,
    unsigned int);
}

static inline long long getint(va_list *ap, int lflag) {
    if (lflag >= 2)
        return va_arg(*ap,
    long long);
    else if (lflag)
        return va_arg(*ap,
    long);
    else
    return va_arg(*ap,
    int);
}

static inline void printnum(putc_t putfn, void *putarg,
                            unsigned long long num, unsigned base, int width, int padc) {
    unsigned char digits[sizeof(num) * CHAR_BIT];
    int pos = 0;

    for (;;) {
        digits[pos++] = num % base;
        if (num < base)
            break;
        num /= base;
    }

    while (width-- > pos) {
        putfn(padc, putarg);
    }
    while (pos-- > 0) {
        putfn(digits[pos] + (digits[pos] >= 10 ? 'a' - 10 : '0'), putarg);
    }
}

void __vxprintf(putc_t putfn, void *putarg, const char *fmt, va_list ap) {
    register const char *p;
    const char *last_fmt;
    register int c;
    unsigned long long num;
    int base, lflag, width, prec;
    char padc;

    for (;;) {
        while ((c = (unsigned char) (*fmt)) != '%') {
            if (c == '\0')
                return;
            fmt++;
            putfn(c, putarg);
        }
        fmt++;

        // Process a format specifier
        last_fmt = fmt;
        padc = ' ';
        width = -1;
        prec = -1;
        lflag = 0;

        reswitch:
        switch (c = (unsigned char) (*fmt++)) {

            // flag to pad on the right
            case '-':
                padc = '-';
                goto reswitch;

                // flag to pad with 0's instead of spaces
            case '0':
                padc = '0';
                goto reswitch;

                // width field
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
                for (prec = 0;; ++fmt) {
                    prec = (prec * 10) + c - '0';
                    c = *fmt;
                    if ((c < '0') || (c > '9'))
                        break;
                }
                goto process_precision;

            case '*':
                prec = va_arg(ap,
                int);
            process_precision:
                if (width < 0)
                    width = prec, prec = -1;
                goto reswitch;

            case '.':
                if (width < 0)
                    width = 0;
                goto reswitch;

            case '#':
                goto reswitch;

                // long flag (doubled for long long)
            case 'l':
                lflag++;
                goto reswitch;

                // character
            case 'c':
                putfn(va_arg(ap,
                int), putarg);
                break;

                // string
            case 's':
                if ((p = va_arg(ap, char *)) == NULL)
                p = "(null)";
                if (width > 0 && padc != '-') {
                    for (width -= strnlen(p, prec); width > 0; width--) {
                        putfn(padc, putarg);
                    }
                }
                for (; (c = *p) != '\0' && ((prec < 0) || (--prec >= 0)); width--) {
                    putfn(c, putarg);
                    p++;
                }
                for (; width > 0; width--)
                    putfn(' ', putarg);
                break;

                // (signed) decimal
            case 'd':
                num = getint(&ap, lflag);
                if ((long long) num < 0) {
                    putfn('-', putarg);
                    num = -(long long) num;
                }
                base = 10;
                goto signed_number;

            case 'f': {
                double fval = va_arg(ap,
                double);
                int frac_width = (prec < 0) ? 6 : prec; // Default precision is 6
                long long int_part = (long long) fval;  // Integer part
                double frac_part = fval - (double) int_part; // Fractional part

                // Handle negative values
                if (fval < 0) {
                    putfn('-', putarg);
                    int_part = -int_part;
                    frac_part = -frac_part;
                }

                // Print the integer part
                printnum(putfn, putarg, (unsigned long long) int_part, 10, width, padc);

                // Print the decimal point and fractional part if precision > 0
                if (frac_width > 0) {
                    putfn('.', putarg);

                    // Scale the fractional part to an integer
                    unsigned long long scale = 1;
                    for (int i = 0; i < frac_width; i++)
                        scale *= 10;

                    unsigned long long frac_int = (unsigned long long) (frac_part * scale +
                                                                        0.5); // Add 0.5 for rounding

                    // Print the fractional part, padded with zeros if necessary
                    printnum(putfn, putarg, frac_int, 10, frac_width, '0');
                }
                break;
            }

                // unsigned decimal
            case 'u':
                base = 10;
                goto unsigned_number;

                // (unsigned) octal
            case 'o':
                // should do something with padding so it's always 3 octets
                base = 8;
                goto unsigned_number;

                // pointer
            case 'p': {
                _Static_assert(sizeof(long) == sizeof(void *),
                               "pointer size mismatch");
                lflag = 1;
                putfn('0', putarg);
                putfn('x', putarg);
                /* fall through to 'x' */
            }

                // (unsigned) hexadecimal
            case 'x':
                base = 16;
            unsigned_number:
                num = getuint(&ap, lflag);
            signed_number:
                printnum(putfn, putarg, num, base, width, padc);
                break;

                // escaped '%' character
            case '%':
                putfn(c, putarg);
                break;

                // unrecognized specifier; treat as literal
            default:
                putfn('%', putarg);
                fmt = last_fmt;
                break;
        }
    }
}
