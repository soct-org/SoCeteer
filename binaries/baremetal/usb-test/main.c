/* =========================================================================
 * USB host DMA test
 *
 * Proves that the PS USB host controller can reach PL-side DRAM.
 *
 * The controller is hardened in the processing system and reaches memory over its own
 * interconnect, so its transactions must leave through `M_AXI_HPM0_LPD` and be decoded by the
 * fabric before they arrive at DRAM. Nothing else in the design masters that way, and no
 * register readback can confirm it - only a transfer can. The test therefore does the smallest
 * amount of xHCI bring-up that ends in the controller reading from and writing to our memory
 * (see xhci.h), and checks the result.
 *
 * Prerequisite: the PS must have been initialized once after power-up by the psu_init that
 * Vivado generates alongside the design; the USB PHY, its clocks and its MIO pins come from
 * there. Without it the controller's registers do not respond.
 * ========================================================================= */

#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>

#include "soct/smoldtb.h"
#include "usbtest.h"
#include "xhci.h"

/* Reach of the controller's DMA, from the USB node's `dma-ranges`. */
static uintptr_t s_dma_base;
static uintptr_t s_dma_limit;

/* Cycles per microsecond, from the cbus clock. */
static unsigned long s_cycles_per_us = 1;

/* =========================================================================
 * Device tree
 * ========================================================================= */

static dtb_node *dt_require_compatible(const char *compat) {
    dtb_node *node = dtb_find_compatible(NULL, compat);
    if (!node) {
        printf("FATAL: no device-tree node with compatible \"%s\" - is this a design with a "
               "processing system?\n", compat);
        abort();
    }
    return node;
}

static void dt_require_reg(dtb_node *node, uintptr_t *base, uintptr_t *size) {
    dtb_prop *reg = dtb_find_prop(node, "reg");
    dtb_pair layout = {dtb_get_addr_cells_for(node), dtb_get_size_cells_for(node)};
    dtb_pair val = {0, 0};
    if (!reg || dtb_read_prop_2(reg, layout, &val) < 1) {
        printf("FATAL: could not read the device-tree reg property\n");
        abort();
    }
    *base = (uintptr_t) val.a;
    if (size) *size = (uintptr_t) val.b;
}

static unsigned long dt_require_u32(dtb_node *node, const char *name) {
    dtb_prop *prop = dtb_find_prop(node, name);
    uintmax_t val = 0;
    if (!prop || dtb_read_prop_1(prop, 1, &val) < 1) {
        printf("FATAL: could not read device-tree property \"%s\"\n", name);
        abort();
    }
    return (unsigned long) val;
}

/* =========================================================================
 * DMA-reachable allocation
 * ========================================================================= */

void delay_us(unsigned long us) {
    unsigned long t0, t1;
    __asm__ volatile("csrr %0, mcycle" : "=r"(t0));
    const unsigned long limit = us * s_cycles_per_us;
    do {
        __asm__ volatile("csrr %0, mcycle" : "=r"(t1));
    } while (t1 - t0 < limit);
}

void *dma_alloc(size_t size, size_t align, const char *what) {
    uint8_t *raw = malloc(size + align);
    if (!raw) {
        printf("FATAL: out of memory allocating the %s (%zu bytes)\n", what, size + align);
        abort();
    }
    uintptr_t addr = ((uintptr_t) raw + (align - 1)) & ~(uintptr_t) (align - 1);
    if (addr < s_dma_base || addr + size > s_dma_limit) {
        printf("FATAL: the %s landed at 0x%lx, outside the window the controller's DMA "
               "reaches (0x%lx..0x%lx). Its transactions would never arrive.\n",
               what, (unsigned long) addr, (unsigned long) s_dma_base,
               (unsigned long) s_dma_limit - 1);
        abort();
    }
    return (void *) addr;
}

/* =========================================================================
 * DWC3
 *
 * The Synopsys core the xHCI registers belong to. psu_init brings up its PHY and clocks; the
 * one thing that still has to be stated is which role it takes, because a core left in device
 * or OTG mode presents no host registers to program.
 * ========================================================================= */

#define DWC3_GCTL          0xC110u
#define DWC3_GCTL_PRTCAPDIR_SHIFT 12
#define DWC3_GCTL_PRTCAPDIR_MASK  (3u << DWC3_GCTL_PRTCAPDIR_SHIFT)
#define DWC3_GCTL_PRTCAP_HOST     1u

static void dwc3_select_host_mode(uintptr_t base) {
    volatile uint32_t *gctl = (volatile uint32_t *) (base + DWC3_GCTL);
    const uint32_t before = *gctl;
    const uint32_t mode = (before & DWC3_GCTL_PRTCAPDIR_MASK) >> DWC3_GCTL_PRTCAPDIR_SHIFT;
    printf("DWC3 GCTL=0x%08" PRIx32 " (port capability %s)\n", before,
           mode == 1 ? "host" : mode == 2 ? "device" : mode == 3 ? "OTG" : "unset");
    if (mode != DWC3_GCTL_PRTCAP_HOST) {
        *gctl = (before & ~DWC3_GCTL_PRTCAPDIR_MASK)
                | (DWC3_GCTL_PRTCAP_HOST << DWC3_GCTL_PRTCAPDIR_SHIFT);
        delay_us(1000);
        printf("  set to host mode, GCTL now 0x%08" PRIx32 "\n", *gctl);
    }
}

/* ========================================================================= */

int main(void) {
    printf("=== USB host DMA test ===\n");

    /* The busy-wait delays count core cycles; the core clock is the cbus clock. */
    dtb_node *cbus = dtb_find("/soc/cbus_clock");
    if (!cbus) {
        printf("FATAL: /soc/cbus_clock not found in the device tree\n");
        abort();
    }
    s_cycles_per_us = dt_require_u32(cbus, "clock-frequency") / 1000000ul;

    /* The USB node's reg is already an address in the PS window, so it is directly usable;
     * dma-ranges describes which part of DRAM the controller can address. */
    dtb_node *usb = dt_require_compatible("snps,dwc3");
    uintptr_t usb_base, usb_size;
    dt_require_reg(usb, &usb_base, &usb_size);

    /* dma-ranges belongs on the node ABOVE the controller: an operating system reads it
     * from the device's parent (Linux's of_dma_configure does), which is why the design
     * interposes a bus node to carry it. Look there when the controller has none of its
     * own, so this test reads the window the same way its consumers do. */
    dtb_prop *ranges = dtb_find_prop(usb, "dma-ranges");
    if (!ranges) {
        dtb_node *bus = dtb_get_parent(usb);

        if (bus)
            ranges = dtb_find_prop(bus, "dma-ranges");
    }
    dtb_triplet layout = {1, 1, 1};
    dtb_triplet dma = {0, 0, 0};
    if (!ranges || dtb_read_prop_3(ranges, layout, &dma) < 1) {
        printf("FATAL: neither the usb node nor its parent bus has a readable dma-ranges; "
               "without it there is no way to know which memory the controller can address\n");
        abort();
    }
    /* dma-ranges is <bus-address cpu-address size>. Addresses written into the controller are
     * bus addresses while malloc hands out CPU addresses, and this test uses one for the other,
     * which only holds while the mapping is an identity. Check it rather than assume it: a
     * translating window would make every pointer here quietly wrong. */
    if (dma.a != dma.b) {
        printf("FATAL: the controller's DMA window translates addresses (bus 0x%lx maps to CPU "
               "0x%lx). This test passes CPU pointers to the controller unchanged and would be "
               "programming the wrong memory.\n",
               (unsigned long) dma.a, (unsigned long) dma.b);
        abort();
    }
    s_dma_base = (uintptr_t) dma.b;
    s_dma_limit = (uintptr_t) dma.b + (uintptr_t) dma.c;

    printf("Controller registers at 0x%lx (%lu KiB); its DMA reaches 0x%lx..0x%lx\n",
           (unsigned long) usb_base, (unsigned long) usb_size / 1024,
           (unsigned long) s_dma_base, (unsigned long) s_dma_limit - 1);

    dwc3_select_host_mode(usb_base);

    xhci x;
    if (xhci_init(&x, usb_base) != 0) {
        printf("\n=== FAILED: the controller did not come up ===\n");
        return 1;
    }
    if (xhci_noop_roundtrip(&x) != 0) {
        printf("\n=== FAILED: the controller cannot reach DRAM ===\n");
        return 1;
    }

    /* The DMA path is proven; what remains between here and a working keyboard is the port
     * itself, which no register readback covers. */
    xhci_watch_ports(&x, 20);

    printf("\n=== PASSED ===\n");
    printf("The controller fetched a command from DRAM and wrote its completion back, so its\n"
           "DMA leaves the processing system through M_AXI_HPM0_LPD and is decoded by the\n"
           "fabric onto memory. The event named the exact command address, and the CPU read\n"
           "the result without any cache maintenance, so the path is coherent as wired.\n");
    return 0;
}
