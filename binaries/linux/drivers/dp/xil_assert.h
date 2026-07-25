/*
 * Assertion shim for the vendored Xilinx sources, kernel flavor: a kernel cannot abort,
 * so a failed assert warns once with a backtrace and returns - the same shape as the
 * BSP's own asserts, which return on failure when asserts are enabled.
 */
#ifndef XIL_ASSERT_H
#define XIL_ASSERT_H

#include <linux/bug.h>

#define Xil_AssertVoid(Expression)                \
    do {                                          \
        if (WARN_ON(!(Expression)))               \
            return;                               \
    } while (0)

/* Returns 0 on failure, as the BSP's own macro does: the vendored sources use this in
 * int- AND pointer-returning functions, and 0 is the one constant valid for both. */
#define Xil_AssertNonvoid(Expression)             \
    do {                                          \
        if (WARN_ON(!(Expression)))               \
            return 0;                             \
    } while (0)

#define Xil_AssertVoidAlways() Xil_AssertVoid(0)
#define Xil_AssertNonvoidAlways() Xil_AssertNonvoid(0)

#endif /* XIL_ASSERT_H */
