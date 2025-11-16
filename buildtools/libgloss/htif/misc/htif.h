#pragma once

#include <stdint.h>
#include <stdlib.h>
#include "htif/constants.h"

extern volatile uint64_t tohost;
extern volatile uint64_t fromhost;

static uint64_t htif_tohost(const uint64_t dev, const uint64_t cmd, const uint64_t payload) {
    return (dev & HTIF_DEV_MASK) << HTIF_DEV_SHIFT | (cmd & HTIF_CMD_MASK) << HTIF_CMD_SHIFT |
        payload & HTIF_PAYLOAD_MASK;
}

extern long htif_syscall(uint64_t syscall,
                         uint64_t a0,
                         uint64_t a1,
                         uint64_t a2,
                         uint64_t a3,
                         uint64_t a4,
                         uint64_t a5,
                         uint64_t a6);

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-function"
static long htif_syscall_0(uint32_t syscall) {
    return htif_syscall(syscall, 0, 0, 0, 0, 0, 0, 0);
}

static long htif_syscall_1(uint32_t syscall, uint64_t a0) {
    return htif_syscall(syscall, a0, 0, 0, 0, 0, 0, 0);
}

static long htif_syscall_2(uint32_t syscall, uint64_t a0, uint64_t a1) {
    return htif_syscall(syscall, a0, a1, 0, 0, 0, 0, 0);
}

static long htif_syscall_3(uint32_t syscall, uint64_t a0, uint64_t a1, uint64_t a2) {
    return htif_syscall(syscall, a0, a1, a2, 0, 0, 0, 0);
}

static long htif_syscall_4(uint32_t syscall, uint64_t a0, uint64_t a1, uint64_t a2, uint64_t a3) {
    return htif_syscall(syscall, a0, a1, a2, a3, 0, 0, 0);
}

static long htif_syscall_5(uint32_t syscall, uint64_t a0, uint64_t a1, uint64_t a2, uint64_t a3, uint64_t a4) {
    return htif_syscall(syscall, a0, a1, a2, a3, a4, 0, 0);
}

static long htif_syscall_6(uint32_t syscall, uint64_t a0, uint64_t a1, uint64_t a2, uint64_t a3, uint64_t a4,
                           uint64_t a5) {
    return htif_syscall(syscall, a0, a1, a2, a3, a4, a5, 0);
}

static long htif_syscall_7(uint32_t syscall, uint64_t a0, uint64_t a1, uint64_t a2, uint64_t a3, uint64_t a4,
                           uint64_t a5, uint64_t a6) {
    return htif_syscall(syscall, a0, a1, a2, a3, a4, a5, a6);
}
#pragma GCC diagnostic pop
