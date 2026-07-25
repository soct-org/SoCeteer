/*
 * Parameter shim for the vendored Xilinx sources. Device discovery happens via the
 * device tree at runtime (see dp-main.c), so nothing from the BSP generator is needed.
 */
#ifndef XPARAMETERS_H
#define XPARAMETERS_H

/* The ZCU104 routes two PS-GTR lanes to the DisplayPort connector ("Dual
 * Lower" in the board preset). */
#define XPAR_PSU_DP_LANE_COUNT 2

#endif /* XPARAMETERS_H */
