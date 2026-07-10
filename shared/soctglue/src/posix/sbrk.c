#include <stddef.h>
#include <stdint.h>
#include <errno.h>
#include "compiler.h"
#include "soct/soctglue.h"

/*
 * Heap layout (see soct.ld):
 *
 *   [_end, __stack_start)          initial heap - always available (the linker
 *                                  reserves __heap_size bytes plus alignment here)
 *   [__stack_start, stacks_end)    per-hart stacks - never touched
 *   [stacks_end, soct_ram_end())   extended heap - available once the DTB has
 *                                  been parsed; bounded by the RAM size the
 *                                  DTB advertises
 *
 * The break starts in the initial region and jumps over the stack region when
 * it would cross into it. Every sbrk() call still returns a pointer to a
 * contiguous block of `incr` bytes; only the single discontinuity between the
 * two regions is skipped, which newlib's malloc handles (non-adjacent sbrk
 * chunks are simply not coalesced). Exhausting the extended region - or the
 * initial one when no DTB is available - fails with ENOMEM, so malloc returns
 * NULL instead of silently growing into unbacked or foreign memory.
 *
 * NOTE for >2 GiB heaps: LEGACY newlib NANO malloc (libc_nano; e.g. newlib
 * 4.5.0.20241231, verified by disassembly) has two hard 32-bit limits:
 *   1. requests >= 2 GiB are rejected outright (MAX_ALLOC_SIZE), and
 *   2. the first-fit remainder is computed in a 32-bit int, so a free chunk is
 *      never split when the remainder would be >= 2 GiB - after freeing a
 *      multi-GiB block, even small allocations may fail with memory free.
 * Newer newlib builds fix both, so behavior depends on the host toolchain's
 * newlib vintage (heap-test probes the linked libc and reports). The full libc
 * (SOCT_LIBC c) has neither limitation in any vintage we checked.
 */

extern char _end[];
extern char __stack_start[];
extern char __stack_size[];

static char *curbrk = _end;

/** First address above the per-hart stack region (hart count from the DTB) */
static char *stacks_end(void) {
    int harts = soct_n_harts();
    if (harts < 1)
        harts = 1;
    return __stack_start + (size_t) harts * (size_t) __stack_size;
}

size_t soct_heap_remaining(void) {
    const uintptr_t ram_end = soct_ram_end();

    if (curbrk <= __stack_start) {
        size_t rem = (size_t) (__stack_start - curbrk);
        if (ram_end != 0) {
            const uintptr_t ext_start = (uintptr_t) stacks_end();
            if (ext_start < ram_end)
                rem += (size_t) (ram_end - ext_start);
        }
        return rem;
    }

    return ram_end > (uintptr_t) curbrk ? (size_t) (ram_end - (uintptr_t) curbrk) : 0;
}

void *_sbrk(ptrdiff_t incr) {
    char *oldbrk = curbrk;
    char *newbrk = oldbrk + incr;

    if (unlikely(incr < 0)) {
        /* Refuse to shrink. dlmalloc's trim path (MORECORE(-n)) interprets the
         * return value as the NEW break, while sbrk classically returns the OLD
         * one - honoring the shrink desynchronizes its top-chunk bookkeeping
         * from the real break. On bare metal releasing memory buys nothing, and
         * a failed trim is benign for every allocator. sbrk(0) still queries. */
        errno = ENOMEM;
        return (void *) (-1);
    }

    if (unlikely(newbrk < oldbrk)) { /* pointer overflow on a huge increment */
        errno = ENOMEM;
        return (void *) (-1);
    }

    /* Would cross from the initial region into the stacks: relocate the break
     * to the extended region above them (requires the DTB for its bounds). */
    if (unlikely(oldbrk <= __stack_start && newbrk > __stack_start)) {
        if (soct_ram_end() == 0) { /* no DTB: the initial region is all we have */
            errno = ENOMEM;
            return (void *) (-1);
        }
        oldbrk = stacks_end();
        newbrk = oldbrk + incr;
    }

    /* Extended region: bounded below by the stacks and above by the RAM end */
    if (oldbrk > __stack_start) {
        const uintptr_t ram_end = soct_ram_end();
        if (unlikely(ram_end == 0
                     || newbrk < oldbrk /* pointer overflow on huge incr */
                     || (uintptr_t) newbrk > ram_end
                     || newbrk < stacks_end())) {
            errno = ENOMEM;
            return (void *) (-1);
        }
    }

    curbrk = newbrk;
    return oldbrk;
}
