/* soct-test.h — shared test helpers for SoCeteer bare-metal test programs
 *
 * Two complementary styles are provided:
 *
 *  Label+format style  (used by tests that track a local failure counter)
 *    TEST_PASS(label, fmt, ...)
 *    TEST_FAIL(label, fmt, ...)
 *    TEST_SKIP(label, fmt, ...)
 *    TEST_HDR(name)          — major test-suite banner  (=== name ===)
 *    TEST_SECTION(name)      — minor sub-section banner (--- name ---)
 *    TEST_RESULT(name, n)    — summary banner with pass/fail and count
 *
 *  Expression style  (used by tests that rely on global pass/fail counters)
 *    TEST(name, expr)        — evaluate expr; increment soct_test_pass/fail
 *    TEST_ERR(name, expr)    — clear errno, then TEST(name, expr)
 *
 * Global counters (one copy per translation unit – intentional for
 * single-TU programs):
 *    soct_test_pass
 *    soct_test_fail
 */

#ifndef SOCT_TEST_H
#define SOCT_TEST_H

#include <stdio.h>
#include <errno.h>
#include <string.h>

/* -------------------------------------------------------------------------
 * Label + format macros
 * ---------------------------------------------------------------------- */

#define TEST_PASS(label, fmt, ...)  printf("  [PASS] %-32s " fmt "\n", (label), ##__VA_ARGS__)
#define TEST_FAIL(label, fmt, ...)  printf("  [FAIL] %-32s " fmt "\n", (label), ##__VA_ARGS__)
#define TEST_SKIP(label, fmt, ...)  printf("  [SKIP] %-32s " fmt "\n", (label), ##__VA_ARGS__)

/** Major banner — printed at the top of each test suite. */
#define TEST_HDR(name)              printf("\n=== %s ===\n", (name))

/** Minor banner — printed before a sub-group of checks within a suite. */
#define TEST_SECTION(name)          printf("\n--- %s ---\n", (name))

/** Summary line.  n = number of failures returned by the suite. */
#define TEST_RESULT(name, n)                                         \
    printf("\n=== %s %s (%d failure%s) ===\n\n",                    \
           (name), (n) == 0 ? "PASSED" : "FAILED",                  \
           (n), (n) == 1 ? "" : "s")

/* -------------------------------------------------------------------------
 * Expression-style macros — require a soct_test_ctx * for counters
 * ---------------------------------------------------------------------- */

/** Context passed to TEST / TEST_ERR to accumulate pass/fail counts. */
typedef struct soct_test_ctx {
    int pass;
    int fail;
} soct_test_ctx;

/**
 * TEST(ctx, name, expr)
 *   Evaluate expr.  Increments ctx->pass on success, ctx->fail on failure.
 *   Prints [PASS] / [FAIL] accordingly; failure output includes errno.
 */
#define TEST(ctx, name, expr)                                               \
    do {                                                                    \
        int _soct_ok = !!(expr);                                            \
        if (_soct_ok) {                                                     \
            printf("  [PASS] %s\n", (name));                               \
            (ctx)->pass++;                                                  \
        } else {                                                            \
            printf("  [FAIL] %s  (errno=%d: %s)\n",                       \
                   (name), errno, strerror(errno));                         \
            (ctx)->fail++;                                                  \
        }                                                                   \
    } while (0)

/**
 * TEST_ERR(ctx, name, expr)
 *   Clears errno before evaluating expr so the error report reflects only
 *   the call under test, then delegates to TEST.
 */
#define TEST_ERR(ctx, name, expr)  do { errno = 0; TEST((ctx), (name), (expr)); } while (0)

#endif /* SOCT_TEST_H */
