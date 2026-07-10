#pragma once
#include <stddef.h>
#include <stdint.h>

/**
 * Get the number of harts or -1 if it cannot be determined
 */
int soct_n_harts(void);


/**
 * End (exclusive) of the RAM range this program runs in, as advertised by the DTB
 * /memory node(s), clamped to the pointer-addressable range. Returns 0 if the DTB
 * has not been parsed (yet) or contains no memory range covering this program.
 * Used by _sbrk to bound the heap; see posix/sbrk.c.
 */
uintptr_t soct_ram_end(void);


/**
 * Upper bound on the additional bytes the heap can still obtain from the system
 * (i.e. what _sbrk can still hand out: the rest of the early-heap region plus the
 * extended region above the stacks, up to the DTB RAM end). Before the DTB is
 * parsed only the early-heap region is counted.
 *
 * NOTE: this is a byte budget, not the largest possible single malloc - allocator
 * metadata, fragmentation, and (for the default nano libc) its 2 GiB request and
 * chunk-split limits reduce what a single allocation can get. See posix/sbrk.c.
 */
size_t soct_heap_remaining(void);


/**
 * Get the current hart ID.
 */
size_t soct_hart_id(void);


/** Set the current hart to sleep until an interrupt is received. */
#define wfi() __asm__ __volatile__ ("wfi")
