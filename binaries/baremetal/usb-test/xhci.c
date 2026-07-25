#include <inttypes.h>
#include <stdio.h>
#include <string.h>

#include "usbtest.h"
#include "xhci.h"

/* Capability registers (offsets from the controller base). */
#define CAP_CAPLENGTH  0x00u /* byte 0; byte 2..3 = HCIVERSION */
#define CAP_HCSPARAMS1 0x04u
#define CAP_HCCPARAMS1 0x10u
#define CAP_DBOFF      0x14u
#define CAP_RTSOFF     0x18u

/* Operational registers (offsets from the operational base). */
#define OP_USBCMD  0x00u
#define OP_USBSTS  0x04u
#define OP_CRCR    0x18u
#define OP_DCBAAP  0x30u
#define OP_CONFIG  0x38u

#define USBCMD_RS    (1u << 0) /* run/stop */
#define USBCMD_HCRST (1u << 1) /* host controller reset */

#define USBSTS_HCH (1u << 0)  /* halted */
#define USBSTS_HSE (1u << 2)  /* host system error - a DMA transaction failed */
#define USBSTS_CNR (1u << 11) /* controller not ready */

#define CRCR_RCS (1u << 0) /* ring cycle state */

/* Interrupter 0 begins at 0x20 in the runtime registers; these are its registers' offsets
 * from the runtime base, not from the interrupter. */
#define IR0_ERSTSZ 0x28u
#define IR0_ERSTBA 0x30u
#define IR0_ERDP   0x38u

/* A TRB is four 32-bit words: parameter (2), status, control. */
#define TRB_WORDS 4
#define TRB_BYTES (TRB_WORDS * 4)

#define TRB_TYPE_NOOP_CMD    23u
#define TRB_TYPE_CMD_COMPL   33u
#define TRB_TYPE_PORT_STATUS 34u

#define TRB_CYCLE          (1u << 0)
#define TRB_TYPE(ctrl)     (((ctrl) >> 10) & 0x3Fu)
#define TRB_MAKE_TYPE(t)   ((uint32_t)(t) << 10)
#define TRB_COMPL_CODE(st) (((st) >> 24) & 0xFFu)

#define COMPL_SUCCESS 1u

/* The register window into the PS is 32 bits wide, so every access is a 32-bit one and the
 * controller's 64-bit registers are written as two halves, low first. */
static inline uint32_t rd32(volatile uint8_t *p) { return *(volatile uint32_t *) p; }

static inline void wr32(volatile uint8_t *p, uint32_t v) { *(volatile uint32_t *) p = v; }

static inline void wr64(volatile uint8_t *p, uint64_t v) {
    wr32(p, (uint32_t) v);
    wr32(p + 4, (uint32_t) (v >> 32));
}

void xhci_report(const xhci *x, const char *tag) {
    const uint32_t cmd = rd32(x->op + OP_USBCMD);
    const uint32_t sts = rd32(x->op + OP_USBSTS);
    printf("[xhci %s] USBCMD=0x%08" PRIx32 " USBSTS=0x%08" PRIx32 " (%s%s%s)\n", tag, cmd, sts,
           (sts & USBSTS_HCH) ? "halted " : "running ",
           (sts & USBSTS_CNR) ? "not-ready " : "",
           (sts & USBSTS_HSE) ? "HOST-SYSTEM-ERROR" : "");
}

/** Poll a 32-bit register until `(reg & mask) == want`, or give up. */
static int wait_for(volatile uint8_t *reg, uint32_t mask, uint32_t want, unsigned ms,
                    const char *what) {
    for (unsigned i = 0; i < ms * 10u; i++) {
        if ((rd32(reg) & mask) == want) return 0;
        delay_us(100);
    }
    printf("FATAL: timed out waiting for %s (register reads 0x%08" PRIx32 ")\n",
           what, rd32(reg));
    return -1;
}

int xhci_init(xhci *x, uintptr_t base) {
    x->cap = (volatile uint8_t *) base;
    const uint32_t caplen_ver = rd32(x->cap + CAP_CAPLENGTH);
    const uint32_t caplen = caplen_ver & 0xFFu;
    printf("xHCI at 0x%lx: CAPLENGTH=%" PRIu32 " HCIVERSION=0x%04" PRIx32 "\n",
           (unsigned long) base, caplen, caplen_ver >> 16);
    if (caplen < 0x20u || caplen > 0xFFu) {
        printf("FATAL: implausible CAPLENGTH - the controller is not responding through the "
               "PS window (did psu_init run?)\n");
        return -1;
    }

    x->op = x->cap + caplen;
    x->rt = x->cap + (rd32(x->cap + CAP_RTSOFF) & ~0x1Fu);
    x->db = x->cap + (rd32(x->cap + CAP_DBOFF) & ~0x3u);

    const uint32_t hcs1 = rd32(x->cap + CAP_HCSPARAMS1);
    const uint32_t max_slots = hcs1 & 0xFFu;
    const uint32_t max_ports = (hcs1 >> 24) & 0xFFu;
    x->max_ports = max_ports;
    const uint32_t hcc1 = rd32(x->cap + CAP_HCCPARAMS1);
    printf("  slots=%" PRIu32 " ports=%" PRIu32 " %s addressing\n",
           max_slots, max_ports, (hcc1 & 1u) ? "64-bit" : "32-bit");

    /* Stop it before resetting: resetting a running controller is undefined. */
    wr32(x->op + OP_USBCMD, rd32(x->op + OP_USBCMD) & ~USBCMD_RS);
    if (wait_for(x->op + OP_USBSTS, USBSTS_HCH, USBSTS_HCH, 100, "the controller to halt")) return -1;

    wr32(x->op + OP_USBCMD, USBCMD_HCRST);
    if (wait_for(x->op + OP_USBCMD, USBCMD_HCRST, 0, 1000, "the reset to complete")) return -1;
    if (wait_for(x->op + OP_USBSTS, USBSTS_CNR, 0, 1000, "the controller to become ready")) return -1;

    /* Rings and contexts. Everything the controller dereferences must be 64-byte aligned and
     * inside the window its DMA can reach - dma_alloc enforces both. */
    x->dcbaa = dma_alloc((max_slots + 1) * sizeof(uint64_t), 64, "device-context array");
    x->cmd_ring = dma_alloc(XHCI_RING_TRBS * TRB_BYTES, 64, "command ring");
    x->evt_ring = dma_alloc(XHCI_RING_TRBS * TRB_BYTES, 64, "event ring");
    x->erst = dma_alloc(TRB_BYTES, 64, "event-ring segment table");
    memset(x->dcbaa, 0, (max_slots + 1) * sizeof(uint64_t));
    memset(x->cmd_ring, 0, XHCI_RING_TRBS * TRB_BYTES);
    memset(x->evt_ring, 0, XHCI_RING_TRBS * TRB_BYTES);
    memset(x->erst, 0, TRB_BYTES);
    x->evt_index = 0;
    /* Rings start out zeroed, so the controller's first pass writes cycle = 1. */
    x->evt_cycle = 1;

    wr32(x->op + OP_CONFIG, max_slots);
    wr64(x->op + OP_DCBAAP, (uint64_t) (uintptr_t) x->dcbaa);
    wr64(x->op + OP_CRCR, (uint64_t) (uintptr_t) x->cmd_ring | CRCR_RCS);

    /* One event-ring segment. ERSTBA is written last: it is what arms the interrupter. */
    x->erst[0] = (uint32_t) (uintptr_t) x->evt_ring;
    x->erst[1] = 0;
    x->erst[2] = XHCI_RING_TRBS;
    x->erst[3] = 0;
    wr32(x->rt + IR0_ERSTSZ, 1);
    wr64(x->rt + IR0_ERDP, (uint64_t) (uintptr_t) x->evt_ring);
    wr64(x->rt + IR0_ERSTBA, (uint64_t) (uintptr_t) x->erst);

    printf("  dcbaa=%p cmd_ring=%p evt_ring=%p erst=%p\n",
           (void *) x->dcbaa, (void *) x->cmd_ring, (void *) x->evt_ring, (void *) x->erst);

    dma_fence();
    wr32(x->op + OP_USBCMD, rd32(x->op + OP_USBCMD) | USBCMD_RS);
    if (wait_for(x->op + OP_USBSTS, USBSTS_HCH, 0, 100, "the controller to start")) return -1;
    xhci_report(x, "running");
    return 0;
}

/**
 * Take the next event off the ring, or NULL when it holds nothing new.
 *
 * The controller writes a TRB's cycle bit last, so one matching our expected cycle is complete.
 * Consuming it advances our position, flips the expected cycle on wrap, and tells the
 * controller how far we have read - without which the ring fills and stalls.
 */
static uint32_t *event_pop(xhci *x) {
    uint32_t *e = &x->evt_ring[x->evt_index * TRB_WORDS];
    dma_fence();
    if ((e[3] & TRB_CYCLE) != x->evt_cycle) return NULL;
    if (++x->evt_index == XHCI_RING_TRBS) {
        x->evt_index = 0;
        x->evt_cycle ^= 1u;
    }
    /* Bit 3 of ERDP clears the event-handler-busy flag along with the dequeue update. */
    wr64(x->rt + IR0_ERDP,
         (uint64_t) (uintptr_t) &x->evt_ring[x->evt_index * TRB_WORDS] | 8u);
    return e;
}

int xhci_noop_roundtrip(xhci *x) {
    uint32_t *cmd = &x->cmd_ring[0];
    const uintptr_t cmd_addr = (uintptr_t) cmd;

    /* A No-Op command does nothing except be executed, which is exactly what is wanted: the
     * controller has to fetch it from our memory and then report on it. */
    cmd[0] = 0;
    cmd[1] = 0;
    cmd[2] = 0;
    cmd[3] = TRB_MAKE_TYPE(TRB_TYPE_NOOP_CMD) | TRB_CYCLE;
    dma_fence();

    printf("Queued a No-Op command at 0x%lx; ringing the command doorbell...\n",
           (unsigned long) cmd_addr);
    wr32(x->db, 0); /* doorbell 0 = the command ring */

    /* Consume the ring until the command's own completion turns up. The ring is shared by
     * everything the controller reports, and a device already attached when it started makes it
     * announce that port before it ever looks at the command ring - so the first event is not
     * necessarily, or even usually, the answer to the doorbell. */
    for (unsigned i = 0; i < 10000u; i++) {
        uint32_t *evt = event_pop(x);
        if (!evt) {
            if (rd32(x->op + OP_USBSTS) & USBSTS_HSE) {
                printf("FATAL: host system error - the controller's DMA was rejected. Its "
                       "transactions are not reaching DRAM.\n");
                xhci_report(x, "hse");
                return -1;
            }
            delay_us(100);
            continue;
        }

        const uint32_t type = TRB_TYPE(evt[3]);
        const uint32_t code = TRB_COMPL_CODE(evt[2]);
        const uint64_t ptr = (uint64_t) evt[0] | ((uint64_t) evt[1] << 32);

        if (type == TRB_TYPE_PORT_STATUS) {
            /* The parameter carries a port number here, not an address. Worth printing: it
             * says a device is attached, which is the next thing anyone asks. */
            printf("  (root-hub port %u changed state - something is plugged in)\n",
                   (unsigned) ((evt[0] >> 24) & 0xFFu));
            continue;
        }
        printf("Event: type=%" PRIu32 " completion=%" PRIu32 " trb_pointer=0x%llx\n",
               type, code, (unsigned long long) ptr);

        if (type != TRB_TYPE_CMD_COMPL) {
            printf("FATAL: expected a Command Completion Event (%u), got type %" PRIu32 "\n",
                   TRB_TYPE_CMD_COMPL, type);
            return -1;
        }
        if (code != COMPL_SUCCESS) {
            printf("FATAL: the command completed with code %" PRIu32 ", not success\n", code);
            return -1;
        }
        if (ptr != (uint64_t) cmd_addr) {
            printf("FATAL: the event names command TRB 0x%llx, but ours is at 0x%lx. The "
                   "controller is not reading the ring we gave it.\n",
                   (unsigned long long) ptr, (unsigned long) cmd_addr);
            return -1;
        }
        return 0;
    }

    printf("FATAL: no completion event after 1 s. The controller either never fetched the "
           "command (DMA read failed) or could not post the event (DMA write failed).\n");
    xhci_report(x, "timeout");
    return -1;
}

/* Root-hub port registers: PORTSC for port n is at 0x400 + 0x10*(n-1) in the operational
 * registers. Only the bits that say whether something is plugged in are decoded here. */
#define OP_PORTSC(n) (0x400u + 0x10u * ((n) - 1u))

#define PORTSC_CCS (1u << 0)  /* current connect status - a device is attached */
#define PORTSC_PED (1u << 1)  /* port enabled */
#define PORTSC_OCA (1u << 3)  /* over-current active */
#define PORTSC_PR  (1u << 4)  /* port reset */
#define PORTSC_PP  (1u << 9)  /* port power - the port is sourcing VBUS */
#define PORTSC_CSC (1u << 17) /* connect status changed (write 1 to clear) */

#define PORTSC_SPEED(v) (((v) >> 10) & 0xFu)

static const char *speed_name(uint32_t code) {
    switch (code) {
        case 0: return "none";
        case 1: return "full";
        case 2: return "low";
        case 3: return "high";
        case 4: return "super";
        default: return "?";
    }
}

static void print_port(unsigned n, uint32_t v) {
    printf("  port %u: PORTSC=0x%08" PRIx32 " connected=%u enabled=%u powered=%u reset=%u "
           "overcurrent=%u speed=%s\n",
           n, v, !!(v & PORTSC_CCS), !!(v & PORTSC_PED), !!(v & PORTSC_PP),
           !!(v & PORTSC_PR), !!(v & PORTSC_OCA), speed_name(PORTSC_SPEED(v)));
}

void xhci_watch_ports(xhci *x, unsigned seconds) {
    printf("\nWatching %u root-hub port(s) for %u s - plug a device in now.\n",
           x->max_ports, seconds);

    uint32_t prev[16];
    for (unsigned n = 1; n <= x->max_ports && n <= 16u; n++) {
        prev[n - 1] = rd32(x->op + OP_PORTSC(n));
        print_port(n, prev[n - 1]);
    }
    /* A port that is not powered can never report a connect, whatever is plugged into it. */
    for (unsigned n = 1; n <= x->max_ports && n <= 16u; n++) {
        if (!(prev[n - 1] & PORTSC_PP)) {
            printf("  NOTE: port %u is not powered. Nothing attached to it can be detected "
                   "until the port sources VBUS.\n", n);
        }
    }

    for (unsigned t = 0; t < seconds * 100u; t++) {
        for (unsigned n = 1; n <= x->max_ports && n <= 16u; n++) {
            const uint32_t v = rd32(x->op + OP_PORTSC(n));
            /* Ignore the change bits themselves when comparing, so one event prints once. */
            if ((v & ~PORTSC_CSC) != (prev[n - 1] & ~PORTSC_CSC) || (v & PORTSC_CSC)) {
                printf("[change]\n");
                print_port(n, v);
                if (v & PORTSC_CSC) wr32(x->op + OP_PORTSC(n), (v & ~PORTSC_PED) | PORTSC_CSC);
                prev[n - 1] = rd32(x->op + OP_PORTSC(n));
            }
        }
        delay_us(10000);
    }

    printf("Final state:\n");
    int any = 0;
    for (unsigned n = 1; n <= x->max_ports && n <= 16u; n++) {
        const uint32_t v = rd32(x->op + OP_PORTSC(n));
        print_port(n, v);
        if (v & PORTSC_CCS) any = 1;
    }
    if (!any) {
        printf("\nNo port ever reported a connection. The controller and its DMA work, so this\n"
               "is below the controller: the port is not sourcing VBUS, or the connector is not\n"
               "in host role. J96 is a micro-AB socket - a plain micro-B lead leaves the ID pin\n"
               "floating, which keeps the board a peripheral and supplies no power to the\n"
               "device. A USB OTG/host adapter grounds ID; a self-powered hub sidesteps VBUS\n"
               "entirely and is the quickest way to tell the two apart.\n");
    } else {
        printf("\nA device was detected. The port and its power are fine, so anything that\n"
               "fails above this point is software: an operating system that misses the same\n"
               "event is missing the interrupt, not the device.\n");
    }
}
