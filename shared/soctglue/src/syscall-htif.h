#pragma once
#include "spinlock.h"
#include "soct/syscalls.h"

extern volatile sc_htif_slot_t tohost;
extern volatile sc_htif_slot_t fromhost;

extern spinlock_t htif_lock;

sc_htif_slot_t htif_tohost(const uint8_t dev, const uint8_t cmd, const uintptr_t payload) {
    return (dev & SOCT_HTIF_DEV_MASK) << SOCT_HTIF_DEV_SHIFT | (cmd & SOCT_HTIF_CMD_MASK) << SOCT_HTIF_CMD_SHIFT |
           (payload & SOCT_HTIF_PAYLOAD_MASK);
}

static bool htif_syscall(volatile sc_arg_t buf[8], bool wait) {
    // Note: The pointer value must not exceed SOCT_HTIF_PAYLOAD_MASK. There is no real way to react to this not being the case.
    sc_htif_slot_t sc = htif_tohost(0, 0, (uintptr_t) buf);
    uint64_t start;
    if (!wait) __asm__ volatile ("rdcycle %0" : "=r"(start));
    spin_lock(&htif_lock);
    wmb();
    tohost = sc;
    while (fromhost == 0) {
        if (!wait) {
            uint64_t now;
            __asm__ volatile ("rdcycle %0" : "=r"(now));
            if (now - start > SOCT_HTIF_PROBE_TIMEOUT_CYCLES) {
                spin_unlock(&htif_lock);
                return false;
            }
        }
    }
    fromhost = 0;
    spin_unlock(&htif_lock);
    rmb();
    return true;
}

static bool soct_htif_present(void) {
    volatile sc_arg_t buf[8] = {SOCT_HTIF_DEV_TEST, 0, 0, 0, 0, 0, 0, 0};
    return htif_syscall(buf, false);
}

static void soct_handle_htif(soct_handler_resp_t *resp,
                             const sc_type_t syscall,
                             const sc_arg_t a0,
                             const sc_arg_t a1,
                             const sc_arg_t a2,
                             const sc_arg_t a3,
                             const sc_arg_t a4,
                             const sc_arg_t a5,
                             const sc_arg_t a6) {
    volatile sc_arg_t buf[8] = {syscall, a0, a1, a2, a3, a4, a5, a6};
    htif_syscall(buf, true);
    resp->ret = (sc_resp_t) buf[0];
    resp->status = SOCT_HANDLER_HANDLED;
}
