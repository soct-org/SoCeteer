#pragma once
#include "spinlock.h"

// To keep this header only, these are defined in soctglue.c
extern volatile uint64_t tohost;
extern volatile uint64_t fromhost;
extern spinlock_t htif_lock;

uint64_t htif_tohost(const uint64_t dev, const uint64_t cmd, const uint64_t payload) {
    return (dev & SOCT_HTIF_DEV_MASK) << SOCT_HTIF_DEV_SHIFT | (cmd & SOCT_HTIF_CMD_MASK) << SOCT_HTIF_CMD_SHIFT |
        (payload & SOCT_HTIF_PAYLOAD_MASK);
}

static bool htif_syscall(volatile uint64_t buf[8], bool wait) {
    uint64_t sc = htif_tohost(0, 0, (uintptr_t)buf);
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
    volatile uint64_t buf[8] = {SOCT_HTIF_DEV_TEST, 0, 0, 0, 0, 0, 0, 0};
    return htif_syscall(buf, false);
}

static void soct_handle_htif(soct_handler_resp_t *resp,
    const uint32_t syscall,
    const uint64_t a0, const uint64_t a1, const uint64_t a2,
    const uint64_t a3, const uint64_t a4, const uint64_t a5,
    const uint64_t a6) {
    volatile uint64_t buf[8] = {syscall, a0, a1, a2, a3, a4, a5, a6};
    htif_syscall(buf, true);
    resp->ret = (int64_t)buf[0];
    resp->status = SOCT_HANDLER_HANDLED;
}