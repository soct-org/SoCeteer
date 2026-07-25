/*
 * Shared plumbing: DMA-safe allocation, ordering and a delay. Implemented in main.c.
 */
#ifndef USB_TEST_USBTEST_H
#define USB_TEST_USBTEST_H

#include <stddef.h>
#include <stdint.h>

/**
 * Allocate `size` bytes aligned to `align`, inside the window the USB controller's DMA can
 * reach, and abort with `what` in the message when that is impossible.
 *
 * The controller masters through the processing system's address map, where the way out to the
 * fabric is one window; only DRAM behind that window is addressable by it. Memory outside it is
 * not slow to reach, it is unreachable, so this fails rather than returning something that
 * would produce silent garbage.
 */
void *dma_alloc(size_t size, size_t align, const char *what);

/** Order our accesses against the controller's. */
static inline void dma_fence(void) {
    __asm__ volatile("fence" ::: "memory");
}

/** Busy-wait, counting core cycles. */
void delay_us(unsigned long us);

#endif /* USB_TEST_USBTEST_H */
