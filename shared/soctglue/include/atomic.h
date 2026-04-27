#pragma once

#include <stdint.h>

/**
 * Save and disable local interrupts.
 * On this bare-metal M-mode target, interrupts are always disabled,
 * so this is a no-op that returns a dummy flags value.
 */
static inline unsigned long local_irq_save(void) {
    return 0;
}

/**
 * Restore local interrupts from a previously saved flags value.
 * No-op on this target — see local_irq_save().
 */
static inline void local_irq_restore(unsigned long flags) {
    (void) flags;
}

/**
 * RISC-V fence instruction: orders memory accesses of type `p` before `s`.
 * p/s can be: r (read), w (write), rw (read+write), i (instruction fetch)
 */
#define fence(p, s) \
    __asm__ __volatile__ ("fence " #p ", " #s : : : "memory")

/** Full read+write memory barrier. */
static inline void mb(void) { fence(rw, rw); }

/** Read-only barrier: orders loads before subsequent loads. */
static inline void rmb(void) { fence(r, r); }

/** Write-only barrier: orders stores before subsequent stores. */
static inline void wmb(void) { fence(w, w); }

/** Acquire barrier: no loads may be reordered after this point. */
static inline void mb_acquire(void) { fence(r, rw); }

/** Release barrier: no stores may be reordered before this point. */
static inline void mb_release(void) { fence(rw, w); }


/** 32-bit atomic integer type. */
typedef int32_t atomic_t;

/**
 * Atomically load a 32-bit value.
 * Uses a sign-extending volatile read. The explicit cast to int32_t
 * before widening to long avoids a redundant sext.w that some compilers
 * emit when reading a volatile int32_t directly into a 64-bit register.
 */
static inline long atomic_load(const atomic_t *p) {
    return __atomic_load_n(p, __ATOMIC_RELAXED);
}


/**
 * Atomically store a 32-bit value.
 * The volatile cast prevents the compiler from eliding or reordering the store.
 */
static inline void atomic_store(atomic_t *p, atomic_t v) {
    __atomic_store_n(p, v, __ATOMIC_RELAXED);
}

/**
 * Atomically swap *p with v, returning the old value, with acquire semantics.
 * Used to take a spinlock: if the returned old value is 0, the lock was free.
 *
 * With the A extension: uses amoswap.w.aq (atomic, acquire-ordered).
 * Without: falls back to IRQ disable + plain load/store (single-hart safe only).
 */
// Instead of atomic_swap_acquire:
static inline long atomic_swap_acquire(atomic_t *p, atomic_t v) {
    return __atomic_exchange_n(p, v, __ATOMIC_ACQUIRE);
}

/**
 * Atomically clear *p (write 0) with release semantics.
 * Used to release a spinlock.
 *
 * With the A extension: uses amoswap.w.rl writing x0 (zero register).
 * Without: issues a release barrier then a plain volatile store.
 */
// Instead of atomic_clear_release:
static inline void atomic_clear_release(atomic_t *p) {
    __atomic_store_n(p, 0, __ATOMIC_RELEASE);
}
