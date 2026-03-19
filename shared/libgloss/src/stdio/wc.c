#include <errno.h>
#include <stdio.h>
#include <wchar.h>

#undef getwc
#undef putwc
#undef ungetwc

wint_t getwc(FILE *stream) {
    int c = fgetc(stream);
    if (c == EOF) {
        return WEOF;
    }
    return (wint_t)(unsigned char)c;
}

wint_t putwc(wchar_t wc, FILE *stream) {
    if ((unsigned long)wc > 0xFFUL) {
        errno = EILSEQ;
        return WEOF;
    }

    int rc = fputc((unsigned char)wc, stream);
    if (rc == EOF) {
        return WEOF;
    }
    return (wint_t)(unsigned char)wc;
}

wint_t ungetwc(wint_t wc, FILE *stream) {
    if (wc == WEOF) {
        return WEOF;
    }
    if ((unsigned long)wc > 0xFFUL) {
        errno = EILSEQ;
        return WEOF;
    }

    int rc = ungetc((unsigned char)wc, stream);
    if (rc == EOF) {
        return WEOF;
    }
    return (wint_t)(unsigned char)wc;
}
