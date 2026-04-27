#pragma once

static void soct_usleep(unsigned us) {
    uintptr_t cycles0;
    uintptr_t cycles1;
    __asm__ volatile ("csrr %0, 0xB00" : "=r" (cycles0));
    for (;;) {
        __asm__ volatile ("csrr %0, 0xB00" : "=r" (cycles1));
        if (cycles1 - cycles0 >= us * 100) break;
    }
}