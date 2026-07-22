/*
 * Shared plumbing: device-tree access and the cycle-counter time base.
 *
 * Both are needed by nearly every module, and neither belongs to any one of
 * them. The device-tree accessors are implemented in main.c, where discovery
 * happens; the time base is header-only because its callers sit in tight
 * sampling loops that must not pay for a call.
 */
#ifndef DP_TEST_DPTEST_H
#define DP_TEST_DPTEST_H

#include <stdint.h>

#include "sleep.h"
#include "soct/smoldtb.h"

/* ---------------------------------------------------------------------------
 * Fail-loud device-tree accessors
 *
 * Every lookup in this test names a piece of the pipeline that the design
 * either has or does not have, so a miss is a build/configuration error, not a
 * runtime condition to degrade around: these abort with the missing name
 * instead of returning a default.
 * ------------------------------------------------------------------------- */

/* First node with the given compatible string; aborts when there is none. */
dtb_node *dt_require_compatible(const char *compat);

/* First reg entry of `node`. `size` may be NULL when only the base is wanted. */
void dt_require_reg(dtb_node *node, uintptr_t *base, uintptr_t *size);

/* Single-cell integer property; aborts when absent or unreadable. */
unsigned long dt_require_u32(dtb_node *node, const char *name);

/* ---------------------------------------------------------------------------
 * Time base
 *
 * Every measurement here comes from mcycle rather than a timer peripheral: the
 * loops being paced are tight enough that a CSR read is the only clock cheap
 * enough not to distort what it measures. The cycles/us rate comes from the
 * device tree (see SoctXil_SetCpuFreqHz).
 * ------------------------------------------------------------------------- */

static inline unsigned long cycles_now(void) {
    unsigned long c;
    __asm__ volatile("csrr %0, mcycle" : "=r"(c));
    return c;
}

static inline unsigned long us_to_cycles(unsigned long us) {
    return us * SoctXil_GetCyclesPerUs();
}

static inline unsigned long cycles_to_us(unsigned long cycles) {
    return cycles / SoctXil_GetCyclesPerUs();
}

#endif /* DP_TEST_DPTEST_H */
