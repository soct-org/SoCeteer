#include <stdint.h>
#include "atomic.h"
#include "spinlock.h"
#include "htif/constants.h"
#include "htif.h"

volatile uint64_t tohost __attribute__ ((section (HTIF_SECTION)));
volatile uint64_t fromhost __attribute__ ((section (HTIF_SECTION)));

static spinlock_t htif_lock = SPINLOCK_INIT;

extern long htif_syscall(uint64_t syscall,
                         uint64_t a0,
                         uint64_t a1,
                         uint64_t a2,
                         uint64_t a3,
                         uint64_t a4,
                         uint64_t a5,
                         uint64_t a6) {
    volatile uint64_t buf[8] = {syscall, a0, a1, a2, a3, a4, a5, a6};
    uint64_t sc = htif_tohost(0, 0, (uintptr_t)&buf);
    spin_lock(&htif_lock);
    wmb();
    tohost = sc;
    while (fromhost == 0) {}
    fromhost = 0;
    spin_unlock(&htif_lock);

    rmb();
    return buf[0];
}
