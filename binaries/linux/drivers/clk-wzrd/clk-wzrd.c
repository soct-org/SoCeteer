// SPDX-License-Identifier: GPL-2.0
/*
 * AXI Clocking Wizard dynamic reconfiguration - see clk-wzrd.h. The register model
 * mirrors the mainline clk-xlnx-clock-wizard driver's whole-MMCM path: write the
 * three configuration words, wait for LOCKED, write LOAD|SEN, wait for relock.
 */
#include <linux/io.h>
#include <linux/iopoll.h>
#include <linux/math64.h>

#include "clk-wzrd.h"

#define WZRD_STATUS            0x004u
#define WZRD_STATUS_LOCKED     0x1u
#define WZRD_CFG_MULT_DIV      0x200u /* div [7:0] | mult [15:8] | mult 1/1000 [25:16] */
#define WZRD_CFG_CLKOUT0       0x208u /* odiv [7:0] | odiv 1/1000 [17:8] */
#define WZRD_CFG_CLKOUT0_PHASE 0x20Cu
#define WZRD_APPLY             0x25Cu
#define WZRD_APPLY_LOAD_SEN    0x3u

int soct_clk_wzrd_solve(const struct soct_clk_wzrd_limits *lim, u32 target_hz,
			struct soct_clk_wzrd_setting *out)
{
	u32 best_err = U32_MAX;
	u32 div, o8, i;

	if (!lim->input_hz || !target_hz)
		return -EDOM;

	/* All window comparisons are exact rationals: pfd = input/div is tested as
	 * input vs bound*div, vco = input*mult/div as input*m8 vs bound*8*div. */
	for (div = 1; div <= 106; div++) {
		if ((u64)lim->pfd_min_hz * div > lim->input_hz ||
		    (u64)lim->pfd_max_hz * div < lim->input_hz)
			continue;
		for (o8 = 8; o8 <= 128 * 8; o8++) {
			/* The mult that hits the target exactly, rounded down to
			 * eighths; it and its neighbor bracket the target. */
			u64 m8_floor = div64_u64((u64)target_hz * div * o8, lim->input_hz);

			for (i = 0; i < 2; i++) {
				u64 m8 = m8_floor + i;
				u64 achieved;
				u32 err;

				if (m8 < 2 * 8 || m8 > 128 * 8)
					continue;
				if (lim->input_hz * m8 < (u64)lim->vco_min_hz * 8 * div ||
				    lim->input_hz * m8 > (u64)lim->vco_max_hz * 8 * div)
					continue;
				achieved = div64_u64((u64)lim->input_hz * m8, (u64)div * o8);
				err = achieved > target_hz ? achieved - target_hz
							   : target_hz - achieved;
				if (err < best_err) {
					best_err = err;
					out->div = div;
					out->mult_eighths = m8;
					out->odiv_eighths = o8;
					out->achieved_hz = achieved;
				}
			}
		}
	}
	/* Within 0.5%: err * 200 <= target. */
	if (best_err == U32_MAX || (u64)best_err * 200 > target_hz)
		return -EDOM;
	return 0;
}

int soct_clk_wzrd_retune(void __iomem *base, const struct soct_clk_wzrd_setting *s)
{
	/* The registers express fractions in thousandths; eighths convert exactly. */
	u32 mult_frac = (s->mult_eighths % 8) * 125;
	u32 odiv_frac = (s->odiv_eighths % 8) * 125;
	u32 st;

	writel((s->odiv_eighths / 8) | (odiv_frac << 8), base + WZRD_CFG_CLKOUT0);
	writel(s->div | ((s->mult_eighths / 8) << 8) | (mult_frac << 16),
	       base + WZRD_CFG_MULT_DIV);
	writel(0, base + WZRD_CFG_CLKOUT0_PHASE);

	/* The wizard only accepts a load while locked. */
	if (read_poll_timeout(readl, st, st & WZRD_STATUS_LOCKED, 100, 100000,
			      false, base + WZRD_STATUS))
		return -EBUSY;
	writel(WZRD_APPLY_LOAD_SEN, base + WZRD_APPLY);
	return read_poll_timeout(readl, st, st & WZRD_STATUS_LOCKED, 100, 1000000,
				 false, base + WZRD_STATUS) ? -ETIMEDOUT : 0;
}
