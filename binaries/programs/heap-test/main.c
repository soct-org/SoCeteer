#include <stdint.h>
#include <inttypes.h>
#include <stdbool.h>
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>

#include "soct/soctglue.h"
#include "soct-test.h"

#ifndef SOCT_LIBC_IS_NANO
#error "Define SOCT_LIBC_IS_NANO=0 or =1"
#endif

/* =========================================================================
 * heap-test — validates soctglue's DTB-bounded heap (posix/sbrk.c)
 *
 * The heap starts in the small initial region below the stacks and extends
 * above them up to soct_ram_end() (from the DTB /memory node). This test:
 *   1. reports the heap geometry,
 *   2. mallocs 1 MiB chunks until NULL and checks the total lands close to
 *      the theoretical capacity (and that errno is ENOMEM),
 *   3. verifies a marker written into every chunk (no aliasing/clobbering),
 *   4. frees everything and re-allocates one big block to check reuse.
 * ========================================================================= */

#define CHUNK (1u << 20)

extern char _end[];
extern char __stack_start[];
extern char __stack_size[];

/* Definitive runtime identification of the LINKED allocator: each newlib
 * malloc defines a marker symbol the other lacks. Weak references resolve to
 * NULL when the corresponding implementation is not in the image. */
extern void *__malloc_free_list __attribute__((weak)); /* newlib nano malloc  */
extern void *__malloc_av_ __attribute__((weak));       /* newlib full malloc  */

/** Allocate `big` bytes, verify head/tail are writable, free. Returns failure count. */
static int reuse_block(size_t big, const char *label) {
    uint8_t *blk = malloc(big);
    if (!blk) {
        TEST_FAIL(label, "malloc(%zu MiB) failed", big >> 20);
        return 1;
    }
    memset(blk, 0x5A, CHUNK);                  /* touch head... */
    memset(blk + big - CHUNK, 0xA5, CHUNK);    /* ...and tail */
    const int bad = (blk[0] != 0x5A || blk[big - 1] != 0xA5);
    if (bad)
        TEST_FAIL(label, "data mismatch in %zu MiB block", big >> 20);
    else
        TEST_PASS(label, "%zu MiB block reused", big >> 20);
    free(blk);
    return bad;
}

int main(void) {
    int f = 0;
    TEST_HDR("Heap range test");

    const uintptr_t ram_end = soct_ram_end();
    const uint64_t stacks_end = (uint64_t) (uintptr_t) __stack_start
                                + (uint64_t) (soct_n_harts() < 1 ? 1 : soct_n_harts())
                                  * (uint64_t) (uintptr_t) __stack_size;

    printf("  _end          = %p\n", (void *) _end);
    printf("  stacks end    = 0x%" PRIx64 "\n", stacks_end);
    printf("  soct_ram_end  = 0x%" PRIxPTR "\n", ram_end);
    printf("  initial brk   = %p\n", sbrk(0));
    printf("  heap remaining= %zu MiB (soct_heap_remaining)\n", soct_heap_remaining() >> 20);

    /* Report the LINKED allocator and cross-check it against the build config -
     * a mismatch means the build system lied to us and every later verdict
     * would be about the wrong allocator. */
    const bool nano_linked = (&__malloc_free_list != NULL);
    const bool full_linked = (&__malloc_av_ != NULL);
    const bool nano_configured = (SOCT_LIBC_IS_NANO != 0);
    printf("  allocator     = %s (configured: %s)\n\n",
           nano_linked ? "newlib nano malloc" : (full_linked ? "newlib full malloc (dlmalloc)" : "UNKNOWN"),
           nano_configured ? "c_nano" : "c");
    if (nano_linked == full_linked || nano_linked != nano_configured) {
        TEST_FAIL("libc identity", "linked allocator (%s%s) does not match SOCT_LIBC (%s) - stale build or link-order problem",
                  nano_linked ? "nano" : "", full_linked ? "full" : "", nano_configured ? "c_nano" : "c");
        f++;
    }

    if (ram_end == 0) {
        TEST_FAIL("soct_ram_end", "DTB memory range not found - extended heap unavailable");
        TEST_RESULT("Heap range test", 1);
        return 1;
    }
    if ((uint64_t) ram_end <= stacks_end) {
        TEST_FAIL("geometry", "RAM end 0x%" PRIxPTR " below stacks end", ram_end);
        TEST_RESULT("Heap range test", 1);
        return 1;
    }

    /* The advertised budget is the ground truth the allocation loop must hit */
    const uint64_t capacity = (uint64_t) soct_heap_remaining();
    const uint64_t expect_mib = capacity >> 20;

    /* Phase 1+2: allocate until NULL, marker in every chunk (chunks form a chain) */
    typedef struct chunk {
        struct chunk *next;
        size_t idx;
    } chunk_t;

    chunk_t *head = NULL;
    uint64_t n = 0;
    errno = 0;
    for (;;) {
        chunk_t *p = malloc(CHUNK);
        if (!p) break;
        p->next = head;
        p->idx = (size_t) n;
        ((volatile uint8_t *) p)[CHUNK - 1] = (uint8_t) (n * 31u); /* touch the tail */
        head = p;
        n++;
        if (n > expect_mib + 64) break; /* runaway guard: sbrk failed to enforce the limit */
    }
    const int malloc_errno = errno;

    printf("  allocated %" PRIu64 " MiB before failure (extended-heap capacity ~%" PRIu64 " MiB)\n",
           n, expect_mib);

    if (n > expect_mib + 2) {
        TEST_FAIL("limit", "allocated past the DTB RAM end - sbrk limit not enforced");
        f++;
    } else if (n + 8 < expect_mib * 9 / 10) {
        TEST_FAIL("capacity", "only %" PRIu64 " of ~%" PRIu64 " MiB allocatable", n, expect_mib);
        f++;
    } else {
        TEST_PASS("capacity", "%" PRIu64 " MiB, within expectations", n);
    }

    if (malloc_errno == ENOMEM) {
        TEST_PASS("errno", "ENOMEM on exhaustion");
    } else {
        TEST_FAIL("errno", "expected ENOMEM, got %d", malloc_errno);
        f++;
    }

    /* Phase 3: verify the chain markers */
    uint64_t bad = 0, seen = 0;
    for (chunk_t *p = head; p; p = p->next, seen++) {
        if (p->idx != (size_t) (n - 1 - seen)) bad++;
        if (((volatile uint8_t *) p)[CHUNK - 1] != (uint8_t) (p->idx * 31u)) bad++;
    }
    if (bad || seen != n) {
        TEST_FAIL("integrity", "%" PRIu64 " bad markers, %" PRIu64 "/%" PRIu64 " chunks seen", bad, seen, n);
        f++;
    } else {
        TEST_PASS("integrity", "all %" PRIu64 " chunk markers intact", n);
    }

    /* Phase 4: free everything, then check reuse of the coalesced free memory.
     *
     * The two libcs behave fundamentally differently here. Newlib's NANO malloc
     * has two hard 2 GiB limits (verified in the vendored toolchain's
     * disassembly of _malloc_r): requests >= 2 GiB are rejected outright
     * (MAX_ALLOC_SIZE, `slli 0x1f` + `bgeu`), and the first-fit remainder is a
     * 32-bit int (`subw`), so a free chunk is skipped when splitting it would
     * leave >= 2 GiB. Reusing a big coalesced chunk therefore only works in the
     * narrow window (chunk - 2 GiB, 2 GiB) - which is empty for chunks >= 4 GiB.
     * The nano build ASSERTS these quirks (so a newlib fix gets noticed) instead
     * of passing or failing on layout luck; the full libc has neither limit. */
    for (chunk_t *p = head; p;) {
        chunk_t *next = p->next;
        free(p);
        p = next;
    }

#if SOCT_LIBC_IS_NANO
    /* LEGACY newlib nano-malloc (e.g. 4.5.0.20241231, confirmed by disassembly)
     * has two 32-bit limits: requests >= 2 GiB are rejected (MAX_ALLOC_SIZE),
     * and free chunks are skipped when the split remainder would be >= 2 GiB
     * (int remainder in _malloc_r). Newer newlib builds fix both, and different
     * host toolchains ship different vintages - so PROBE the linked libc
     * instead of asserting either behavior. */
    const uint64_t ext = (uint64_t) ram_end - stacks_end; /* the big coalesced chunk */
    if (ext < (3ull << 30)) {
        /* Far away from the 2 GiB limits - nano behaves like any allocator */
        f += reuse_block((size_t) ((capacity / 2) & ~(uint64_t) (CHUNK - 1)), "reuse");
    } else {
        /* volatile pointer objects + real writes: an unused malloc/free pair is
         * legally removed by GCC (allocation DCE), which would fake success
         * without ever calling the allocator */
        void *volatile p1 = malloc((size_t) (2048ull << 20));
        const bool cap_2g = (p1 == NULL);       /* legacy: >= 2 GiB rejected     */
        if (p1) {
            *(volatile uint8_t *) p1 = 0xAB;
            free(p1);
        }
        void *volatile p2 = malloc((size_t) (32ull << 20));
        const bool rem_skip = (p2 == NULL);     /* legacy: huge chunk not split  */
        if (p2) {
            *(volatile uint8_t *) p2 = 0xCD;
            free(p2);
        }

        TEST_PASS("nano probes", "2 GiB request: %s, small request on huge chunk: %s",
                  cap_2g ? "rejected (legacy nano)" : "OK (fixed nano)",
                  rem_skip ? "fails (legacy nano)" : "OK (fixed nano)");
        if (cap_2g != rem_skip) {
            TEST_FAIL("nano probes", "the two legacy limits should appear as a pair - allocator behavior is inconsistent");
            f++;
        }

        if (!cap_2g && !rem_skip) {
            /* Fixed nano: full-libc semantics */
            f += reuse_block((size_t) ((capacity / 2) & ~(uint64_t) (CHUNK - 1)), "reuse");
        } else {
            /* Legacy nano: reuse only works in the window (ext - 2 GiB, 2 GiB) */
            const uint64_t lo = ext - (2048ull << 20);
            const uint64_t hi = (2048ull << 20);
            if (lo + (2ull << 20) >= hi) {
                TEST_SKIP("reuse", "no legacy-nano-reusable size for a %" PRIu64 " MiB chunk (window empty) - use SOCT_LIBC c for such heaps", ext >> 20);
            } else {
                f += reuse_block((size_t) (((lo + hi) / 2) & ~(uint64_t) (CHUNK - 1)), "reuse");
            }
        }
    }
#else
    /* Full libc: half the capacity must simply work */
    f += reuse_block((size_t) ((capacity / 2) & ~(uint64_t) (CHUNK - 1)), "reuse");
    /* ...and so must a single > 2 GiB block - the main reason to pick SOCT_LIBC c */
    if (capacity > (5ull << 29) + (256ull << 20)) {
        f += reuse_block((size_t) (5ull << 29) /* 2.5 GiB */, "reuse >2GiB");
    }
#endif

    TEST_RESULT("Heap range test", f);
    printf("=== TOTAL: %d failure%s ===\n", f, f == 1 ? "" : "s");
    return f == 0 ? 0 : 1;
}
