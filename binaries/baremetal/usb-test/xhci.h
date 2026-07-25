/*
 * Minimal xHCI host-controller bring-up - enough to make the controller perform DMA, and no
 * more. There is no enumeration here, no slots, no transfer rings and no devices.
 *
 * The question this answers is whether the PS USB controller's DMA master reaches the PL-side
 * DRAM at all: it sits on the processing system's interconnect, and its transactions have to
 * leave through the M_AXI_HPM0_LPD port and be decoded by the fabric before they arrive. That
 * is a path no other master in this design uses, and it cannot be checked by reading registers.
 *
 * A No-Op command settles it, in both directions at once. The controller must fetch the command
 * TRB from the command ring (a DMA READ of our memory) and post a Command Completion Event to
 * the event ring (a DMA WRITE). The event carries the address of the command it executed, so a
 * matching pointer proves the controller really read the ring rather than inventing a reply.
 */
#ifndef USB_TEST_XHCI_H
#define USB_TEST_XHCI_H

#include <stdint.h>

/** Register windows of one controller, resolved from CAPLENGTH/RTSOFF/DBOFF. */
typedef struct {
    volatile uint8_t *cap; /* capability registers, i.e. the controller base */
    volatile uint8_t *op;  /* operational registers */
    volatile uint8_t *rt;  /* runtime registers (interrupters) */
    volatile uint8_t *db;  /* doorbell array */

    uint64_t *dcbaa;    /* device-context base-address array */
    uint32_t *cmd_ring; /* command ring segment */
    uint32_t *evt_ring; /* event ring segment */
    uint32_t *erst;     /* event-ring segment table */
    unsigned evt_index; /* next event slot to inspect */
    unsigned evt_cycle; /* cycle bit an event must have to be ours */
    unsigned max_ports; /* root-hub ports the controller reports */
} xhci;

/** Ring sizes in TRBs. One segment each is plenty for a single command. */
#define XHCI_RING_TRBS 64

/**
 * Halt, reset and configure the controller, place its rings in DRAM and start it.
 *
 * Every structure is allocated through `dma_alloc` (see main.c), which refuses memory the
 * controller could not reach.
 *
 * @return 0 on success, non-zero if the controller never left reset or never started
 */
int xhci_init(xhci *x, uintptr_t base);

/**
 * Queue a No-Op command, ring the doorbell and wait for its completion event.
 *
 * @return 0 when the event arrived and named our command TRB, non-zero otherwise
 */
int xhci_noop_roundtrip(xhci *x);

/** Print the controller's status registers, for when something did not work. */
void xhci_report(const xhci *x, const char *tag);

/**
 * Poll the root-hub port registers for `seconds`, reporting every change.
 *
 * Answers a question no amount of driver debugging can: whether a device plugged into the
 * connector is seen electrically at all. The connect bit is set by the port's own receiver, so
 * if it never rises, nothing arrived - the port is not supplying VBUS, or the cable does not
 * put the connector in host role - and no software above it could have noticed either.
 *
 * This polls the registers directly and never waits for an interrupt, so it also separates a
 * dead interrupt path from a dead port: a connect seen here but missed by an operating system
 * is an interrupt problem, not a wiring one.
 */
void xhci_watch_ports(xhci *x, unsigned seconds);

#endif /* USB_TEST_XHCI_H */
