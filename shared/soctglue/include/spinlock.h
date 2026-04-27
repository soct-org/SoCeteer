#pragma once

#include "atomic.h"

/** A simple test-and-set spinlock. Zero means unlocked, non-zero means locked. */
typedef struct {
    atomic_t lock;
} spinlock_t;

/** Static initializer for a spinlock in the unlocked state. */
#define SOCT_SPINLOCK_INIT { 0U }

/**
 * Acquire the spinlock, busy-waiting until it is free.
 *
 * Uses a test-and-test-and-set (TTAS) pattern on targets with the A extension:
 * first spin with a plain load (cheap, stays in cache) until the lock looks
 * free, then attempt the atomic swap. This avoids hammering the bus with
 * amoswap while the lock is held by another hart.
 *
 * On targets without the A extension the inner while is omitted and we just
 * retry the swap directly (single-hart fallback in atomic.h handles safety).
 *
 * The swap uses acquire semantics so no memory access inside the critical
 * section can be reordered before the lock is taken.
 */
static inline void spin_lock(spinlock_t *lock) {
    do {
#ifdef __riscv_atomic
        /* Wait (load-only) until lock appears free before attempting swap */
        while (atomic_load(&lock->lock));
#endif
        /* Atomically set lock to -1; retry if old value was non-zero (locked) */
    } while (atomic_swap_acquire(&lock->lock, -1));
}

/**
 * Release the spinlock.
 * Clears the lock to 0 with release semantics, ensuring all memory accesses
 * inside the critical section are visible before the lock is released.
 */
static inline void spin_unlock(spinlock_t *lock) {
    atomic_clear_release(&lock->lock);
}