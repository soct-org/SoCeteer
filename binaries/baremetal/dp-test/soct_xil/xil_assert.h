/*
 * Assertion shim for the vendored Xilinx sources: a failed assert prints the
 * location and aborts (no silent continue-with-asserts-disabled mode).
 */
#ifndef XIL_ASSERT_H
#define XIL_ASSERT_H

#include <stdio.h>
#include <stdlib.h>

#define Xil_AssertVoid(Expression)                                          \
    do {                                                                    \
        if (!(Expression)) {                                                \
            printf("XIL ASSERT FAILED %s:%d: %s\n", __FILE__, __LINE__,     \
                   #Expression);                                            \
            abort();                                                        \
        }                                                                   \
    } while (0)

#define Xil_AssertNonvoid(Expression) Xil_AssertVoid(Expression)
#define Xil_AssertVoidAlways() Xil_AssertVoid(0)
#define Xil_AssertNonvoidAlways() Xil_AssertVoid(0)

#endif /* XIL_ASSERT_H */
