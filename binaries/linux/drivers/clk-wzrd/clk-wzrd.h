/* SPDX-License-Identifier: GPL-2.0 */
/*
 * AXI Clocking Wizard dynamic reconfiguration (PG065): solve an MMCM divider
 * setting for a target frequency and retune a wizard to it through its AXI4-Lite
 * interface. Nothing here is video-specific - any wizard generated with
 * USE_DYN_RECONFIG can be driven with this. No Kbuild on purpose: this directory
 * is not a module of its own; a consuming module compiles clk-wzrd.c in via its
 * extra-sources.txt.
 */
#ifndef SOCT_CLK_WZRD_H
#define SOCT_CLK_WZRD_H

#include <linux/types.h>

/* The MMCM's analog window - board facts the device tree carries (the generator
 * knows the speed grade; a runtime solver must not guess it). */
struct soct_clk_wzrd_limits {
	u32 input_hz;
	u32 vco_min_hz, vco_max_hz;
	u32 pfd_min_hz, pfd_max_hz;
};

/* One solved configuration: output = input * mult / (div * odiv), the fractional
 * fields in eighths (the MMCM's granularity - CLKFBOUT_MULT_F and CLKOUT0_DIVIDE_F
 * step in 0.125). */
struct soct_clk_wzrd_setting {
	u32 div, mult_eighths, odiv_eighths;
	u32 achieved_hz;
};

/*
 * Finds the divider setting closest to `target_hz` within the limits - the same
 * exhaustive search the generator runs for the synthesized clock (integer DIVCLK
 * 1..106, eighth-step mult 2..128 / odiv 1..128, VCO and PFD windows honored).
 * Returns -EDOM when nothing lands within 0.5% (the slack CTA-861 sinks accept
 * on a pixel clock). Only CLKOUT0 divides fractionally, so this is only valid
 * for a wizard's first output clock.
 */
int soct_clk_wzrd_solve(const struct soct_clk_wzrd_limits *lim, u32 target_hz,
			struct soct_clk_wzrd_setting *out);

/*
 * Programs and applies a solved setting. Returns -EBUSY when the wizard is not
 * locked to begin with (a load is only accepted while locked, so this points at
 * the input clock), -ETIMEDOUT when it does not relock on the new setting.
 *
 * The caller owns the consequences of the output clock stopping mid-retune: cores
 * it clocks see their clock pause and - in designs whose reset rides the wizard's
 * LOCKED output - a reset cycle. Reprogram them afterwards, and allow the reset
 * synchronizer time to release.
 */
int soct_clk_wzrd_retune(void __iomem *base, const struct soct_clk_wzrd_setting *s);

#endif
