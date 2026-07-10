#include <stdint.h>
#include <inttypes.h>
#include <stdbool.h>
#include <string.h>
#include <stdio.h>

#include "soct/smoldtb.h"
#include "soct-test.h"

/* =========================================================================
 * mem-test — full-range DRAM test
 *
 * Verifies that every byte of DRAM advertised in the DTB is addressable,
 * unique (no aliasing) and retains data. Designed to validate the AXI
 * address deinterleaver in multi-channel (interleaved) memory designs:
 * with N channels and B-byte blocks, consecutive B-byte lines alternate
 * between DDR controllers, and a compaction bug shows up as either
 * aliasing (two CPU addresses hitting the same DRAM cell) or holes
 * (addresses that don't retain data).
 *
 * Everything is derived at runtime:
 *   - RAM ranges       : DTB /memory* nodes (all reg pairs)
 *   - hart count       : DTB /cpus (to size the reserved stack area)
 *   - wall clock       : CLINT mtime, located via DTB (riscv,clint0)
 *   - own footprint    : linker symbols (__stack_start, __stack_size)
 *
 * Test suites (fast to slow):
 *   1. capacity reality check  — probes power-of-two offsets across the
 *      claimed range with pre-announced addresses. A DIMM smaller than the
 *      configured capacity shows up as aliasing (with the measured real
 *      capacity) or as a hang at a known, printed address.
 *   2. interleave marker test  — unique marker per 64 B line in windows at
 *      the start, the 4 GiB boundary, and the top of RAM; all writes
 *      complete before any verify, so cross-window aliasing is caught.
 *   3. access width test       — 8/16/32/64-bit stores straddling
 *      channel-interleave line boundaries (catches WSTRB routing bugs).
 *   4. burst copy test         — memcpy/memcmp of large blocks across
 *      line boundaries (back-to-back bursts).
 *   5. full-range line edge test — one unique marker per 64 B line over the
 *      WHOLE testable range, verified only after every line is written.
 *      Address-mapping bugs alias whole lines onto whole lines, so this
 *      already provides full-range aliasing/hole coverage at ~8x the speed
 *      of the word sweep.
 *   6. full moving-inversion sweep — write pattern(addr) to EVERY word of
 *      the testable range, verify + write inverted, verify. Flips every
 *      bit both ways and catches aliasing anywhere in the range.
 * ========================================================================= */

#define LINE_BYTES      64ull            /* interleave block = cache line   */
#define WIN_BYTES       (4ull << 20)     /* marker-test window size         */
#define CHUNK_BYTES     (256ull << 20)   /* progress print interval         */
#define SCRUB_BYTES     (512ull << 10)   /* > any L1/L2 on these designs    */
#define MAX_RANGES      8
#define MAX_ERR_PRINT   8
#define SEED_A          UINT64_C(0x9E3779B97F4A7C15)

typedef uintptr_t word_t;                /* native access width             */
#define WORD_BYTES      sizeof(word_t)

/* Linker symbols (see shared/soctglue/soct.ld) — addresses, not variables */
extern char __stack_start[];
extern char __stack_size[];

/* -------------------------------------------------------------------------
 * Pattern: murmur3 finalizer over the 64-bit address. Any single-bit
 * difference between two addresses yields a different word, so aliased
 * addresses disagree no matter which address bit the mapping dropped.
 * ---------------------------------------------------------------------- */
static inline uint64_t mix64(uint64_t x) {
    x ^= x >> 33;
    x *= UINT64_C(0xff51afd7ed558ccd);
    x ^= x >> 33;
    x *= UINT64_C(0xc4ceb9fe1a85ec53);
    x ^= x >> 33;
    return x;
}

static inline word_t pat(uint64_t addr, uint64_t seed) {
    return (word_t) mix64(addr ^ seed);
}

/* -------------------------------------------------------------------------
 * DTB helpers
 * ---------------------------------------------------------------------- */

typedef struct {
    uint64_t base;
    uint64_t size;
} ram_range_t;

/* Collect all (base, size) pairs from every /...@ node with
 * device_type == "memory". Returns the number of ranges found. */
static size_t dtb_ram_ranges(ram_range_t *out, size_t max) {
    size_t cnt = 0;
    dtb_node *root = dtb_find("/");
    if (!root) return 0;
    for (dtb_node *n = dtb_get_child(root); n; n = dtb_get_sibling(n)) {
        dtb_prop *dp = dtb_find_prop(n, "device_type");
        if (!dp) continue;
        const char *s = dtb_read_prop_string(dp, 0);
        if (!s || strcmp(s, "memory") != 0) continue;
        dtb_prop *reg = dtb_find_prop(n, "reg");
        if (!reg) continue;
        dtb_pair layout = {dtb_get_addr_cells_for(n), dtb_get_size_cells_for(n)};
        size_t pairs = dtb_read_prop_2(reg, layout, NULL);
        if (pairs == 0) continue;
        dtb_pair vals[MAX_RANGES];
        if (pairs > MAX_RANGES) pairs = MAX_RANGES;
        dtb_read_prop_2(reg, layout, vals);
        for (size_t i = 0; i < pairs && cnt < max; i++) {
            out[cnt].base = (uint64_t) vals[i].a;
            out[cnt].size = (uint64_t) vals[i].b;
            cnt++;
        }
    }
    /* insertion sort by base, then merge adjacent/overlapping */
    for (size_t i = 1; i < cnt; i++) {
        ram_range_t key = out[i];
        size_t j = i;
        while (j > 0 && out[j - 1].base > key.base) {
            out[j] = out[j - 1];
            j--;
        }
        out[j] = key;
    }
    size_t m = 0;
    for (size_t i = 1; i < cnt; i++) {
        if (out[i].base <= out[m].base + out[m].size) {
            uint64_t end = out[i].base + out[i].size;
            if (end > out[m].base + out[m].size)
                out[m].size = end - out[m].base;
        } else {
            out[++m] = out[i];
        }
    }
    return cnt ? m + 1 : 0;
}

static int dtb_nharts(void) {
    dtb_node *cpus = dtb_find("/cpus");
    if (!cpus) return 1;
    int n = 0;
    for (dtb_node *c = dtb_get_child(cpus); c; c = dtb_get_sibling(c)) {
        dtb_prop *dp = dtb_find_prop(c, "device_type");
        if (!dp) continue;
        const char *s = dtb_read_prop_string(dp, 0);
        if (s && strcmp(s, "cpu") == 0) n++;
    }
    return n > 0 ? n : 1;
}

/* -------------------------------------------------------------------------
 * Wall clock via CLINT mtime (located through the DTB). Falls back to a
 * disabled clock if no CLINT is found — the test still runs, just without
 * throughput numbers.
 * ---------------------------------------------------------------------- */

static volatile uint32_t *s_mtime_lo = NULL;  /* mtime is at CLINT + 0xBFF8 */
static uint64_t s_timebase_hz = 0;

static void clock_init(void) {
    dtb_node *clint = dtb_find_compatible(NULL, "riscv,clint0");
    if (!clint) return;
    dtb_prop *reg = dtb_find_prop(clint, "reg");
    if (!reg) return;
    dtb_pair layout = {dtb_get_addr_cells_for(clint), dtb_get_size_cells_for(clint)};
    dtb_pair val = {0, 0};
    if (dtb_read_prop_2(reg, layout, &val) < 1) return;
    s_mtime_lo = (volatile uint32_t *) (uintptr_t) ((uint64_t) val.a + 0xBFF8u);

    dtb_node *cpus = dtb_find("/cpus");
    if (cpus) {
        dtb_prop *tb = dtb_find_prop(cpus, "timebase-frequency");
        if (tb) {
            uintmax_t hz = 0;
            if (dtb_read_prop_1(tb, 1, &hz) >= 1) s_timebase_hz = (uint64_t) hz;
        }
    }
    if (s_timebase_hz == 0) s_mtime_lo = NULL;
}

static uint64_t clock_now(void) {
    if (!s_mtime_lo) return 0;
    uint32_t hi, lo, hi2;
    do {
        hi = s_mtime_lo[1];
        lo = s_mtime_lo[0];
        hi2 = s_mtime_lo[1];
    } while (hi != hi2);
    return ((uint64_t) hi << 32) | lo;
}

/* MiB/s for `bytes` processed in `ticks` timebase ticks (0 if unknown) */
static unsigned mibps(uint64_t bytes, uint64_t ticks) {
    if (!s_mtime_lo || ticks == 0) return 0;
    return (unsigned) ((bytes * s_timebase_hz) / (ticks * (1ull << 20)));
}

/* -------------------------------------------------------------------------
 * Cache scrub: stream-read a region larger than any cache so previously
 * written lines are evicted to DRAM and verification reads really hit the
 * memory controller instead of the L1.
 * ---------------------------------------------------------------------- */

static volatile word_t s_scrub_sink;

static void cache_scrub(uint64_t from) {
    word_t acc = 0;
    volatile const word_t *p = (volatile const word_t *) (uintptr_t) from;
    for (uint64_t i = 0; i < SCRUB_BYTES / WORD_BYTES; i += LINE_BYTES / WORD_BYTES)
        acc ^= p[i];
    s_scrub_sink = acc;
    __asm__ volatile ("fence rw,rw" ::: "memory");
}

/* -------------------------------------------------------------------------
 * Testable window computation
 * ---------------------------------------------------------------------- */

typedef struct {
    uint64_t start;
    uint64_t end;    /* exclusive */
} window_t;

static size_t compute_windows(const ram_range_t *ranges, size_t n_ranges,
                              window_t *out, size_t max, uint64_t *clamped) {
    /* Program footprint: text/data/bss/heap end at __stack_start, followed
     * by one stack of __stack_size per hart (crt0 indexes stacks by hartid). */
    uint64_t reserved_end = (uint64_t) (uintptr_t) __stack_start
                            + (uint64_t) dtb_nharts() * (uint64_t) (uintptr_t) __stack_size;
    reserved_end = (reserved_end + (WIN_BYTES - 1)) & ~(WIN_BYTES - 1); /* margin */

    uint64_t addr_limit = (uint64_t) UINTPTR_MAX;
    if (addr_limit != UINT64_MAX) addr_limit += 1;  /* 2^32 on RV32 */

    *clamped = 0;
    size_t cnt = 0;
    for (size_t i = 0; i < n_ranges && cnt < max; i++) {
        uint64_t start = ranges[i].base;
        uint64_t end = ranges[i].base + ranges[i].size;
        if (start < reserved_end) start = reserved_end;
        if (addr_limit != 0 && end > addr_limit) {  /* addr_limit==0 means 2^64 wrapped */
            *clamped += end - addr_limit;
            end = addr_limit;
        }
        if (start + WIN_BYTES > end) continue;
        out[cnt].start = start;
        out[cnt].end = end;
        cnt++;
    }
    return cnt;
}

/* =========================================================================
 * Suite 1: capacity reality check
 *
 * The DIMM's SPD EEPROM (which names the installed part) hangs off the PS
 * I2C bus and is unreachable from this RISC-V design, so this is the
 * software equivalent: verify that the DTB-claimed capacity is actually
 * backed by distinct, decodable memory. A marker is written at power-of-two
 * offsets from a reference cell, each probe address announced BEFORE it is
 * touched. A DIMM smaller than the configured capacity shows up either as
 * aliasing (probes wrap onto the reference cell -> real capacity printed)
 * or as a hang right after an announced probe address (the DDR controller
 * does not decode it -> decode window edge identified).
 * ========================================================================= */

#define SEED_PROBE (SEED_A ^ UINT64_C(0xCAFE))

static bool addr_testable(const window_t *wins, size_t n_wins, uint64_t a) {
    for (size_t i = 0; i < n_wins; i++)
        if (a >= wins[i].start && a + WORD_BYTES <= wins[i].end) return true;
    return false;
}

static int test_capacity_probe(const window_t *wins, size_t n_wins) {
    int f = 0;
    TEST_HDR("Capacity reality check");

    const uint64_t ref = wins[0].start;
    const uint64_t end = wins[n_wins - 1].end;

    printf("  reference cell 0x%" PRIx64 ", memory claimed up to 0x%" PRIx64 "\n", ref, end);
    printf("  NOTE: if the output stops after a 'probing' line, the DDR controller does not\n");
    printf("        decode that address - the installed DIMM is smaller than the configured\n");
    printf("        capacity. Check --ext-mem-part / DDR4PartRegistry.\n\n");

    /* Probe offsets: every power of two that lands in a testable window,
     * plus the very last line of RAM (the decode-window edge detector). */
    uint64_t offs[64];
    size_t np = 0;
    for (uint64_t p = 1ull << 20; ref + p + WORD_BYTES <= end && np < 63; p <<= 1)
        if (addr_testable(wins, n_wins, ref + p)) offs[np++] = p;
    offs[np++] = (end - LINE_BYTES) - ref;

    *(volatile word_t *) (uintptr_t) ref = pat(ref, SEED_PROBE);
    __asm__ volatile ("fence rw,rw" ::: "memory");

    for (size_t i = 0; i < np; i++) {
        uint64_t a = ref + offs[i];
        printf("  probing +0x%010" PRIx64 " (0x%" PRIx64 ")\n", offs[i], a);
        fflush(stdout);
        *(volatile word_t *) (uintptr_t) a = pat(a, SEED_PROBE);
        (void) *(volatile const word_t *) (uintptr_t) a;
    }
    __asm__ volatile ("fence rw,rw" ::: "memory");
    cache_scrub(ref);

    /* Every probe decoded - now check that they hit DISTINCT memory cells.
     * With a wrap period W (a DIMM smaller than claimed), all power-of-two
     * offsets >= W collapse onto the reference cell, so the FIRST offset
     * whose marker does not read back is the real capacity. */
    uint64_t wrap = 0;
    for (size_t i = 0; i < np && wrap == 0; i++) {
        uint64_t a = ref + offs[i];
        if (*(volatile const word_t *) (uintptr_t) a != pat(a, SEED_PROBE)) wrap = offs[i];
    }
    bool ref_ok = *(volatile const word_t *) (uintptr_t) ref == pat(ref, SEED_PROBE);

    if (wrap == 0 && ref_ok) {
        TEST_PASS("distinct probes", "%zu probes decoded and independent - claimed range looks fully backed", np);
    } else {
        if (wrap) {
            TEST_FAIL("aliasing", "probe at +0x%" PRIx64 " lost its marker - memory wraps: real capacity looks like 0x%" PRIx64 " B", wrap, wrap);
        }
        if (!ref_ok) {
            TEST_FAIL("reference cell", "clobbered by a probe write - confirms address aliasing");
        }
        f++;
    }

    TEST_RESULT("Capacity reality check", f);
    return f;
}

/* =========================================================================
 * Suite 2: interleave marker test
 *
 * Windows at the start of the testable range, around the 4 GiB boundary
 * (start of the second AddressSet on VCU118-class designs) and at the top
 * of RAM. One unique marker per 64 B line. ALL markers are written before
 * ANY are verified, so aliasing between any two windows is detected.
 * ========================================================================= */

static int test_interleave_markers(const window_t *wins, size_t n_wins) {
    int f = 0;
    TEST_HDR("Interleave marker test");

    /* Build the probe windows */
    window_t probes[8];
    size_t np = 0;
    const window_t *w0 = &wins[0];
    const window_t *wl = &wins[n_wins - 1];

    probes[np++] = (window_t) {w0->start, w0->start + WIN_BYTES};
    uint64_t four_gib = UINT64_C(0x100000000);
    for (size_t i = 0; i < n_wins; i++) {
        if (wins[i].start + WIN_BYTES < four_gib - WIN_BYTES && four_gib + WIN_BYTES <= wins[i].end) {
            probes[np++] = (window_t) {four_gib - WIN_BYTES, four_gib + WIN_BYTES};
            break;
        }
    }
    if (wl->end - WIN_BYTES > probes[0].end)
        probes[np++] = (window_t) {wl->end - WIN_BYTES, wl->end};

    for (size_t i = 0; i < np; i++)
        printf("  window %zu: [0x%" PRIx64 ", 0x%" PRIx64 ")\n", i, probes[i].start, probes[i].end);

    /* Phase 1: write one marker per line in every window */
    for (size_t i = 0; i < np; i++) {
        for (uint64_t a = probes[i].start; a < probes[i].end; a += LINE_BYTES)
            *(volatile word_t *) (uintptr_t) a = pat(a, SEED_A);
    }
    __asm__ volatile ("fence rw,rw" ::: "memory");
    cache_scrub(w0->start);

    /* Phase 2: verify all windows */
    for (size_t i = 0; i < np; i++) {
        uint64_t bad = 0, first_bad = 0;
        word_t got0 = 0, exp0 = 0;
        for (uint64_t a = probes[i].start; a < probes[i].end; a += LINE_BYTES) {
            word_t got = *(volatile const word_t *) (uintptr_t) a;
            word_t exp = pat(a, SEED_A);
            if (got != exp) {
                if (bad == 0) { first_bad = a; got0 = got; exp0 = exp; }
                bad++;
            }
        }
        char label[32];
        snprintf(label, sizeof label, "window %zu markers", i);
        if (bad) {
            TEST_FAIL(label, "%" PRIu64 " bad lines, first @0x%" PRIx64
                      " got 0x%" PRIxPTR " exp 0x%" PRIxPTR, bad, first_bad,
                      (uintptr_t) got0, (uintptr_t) exp0);
            f++;
        } else {
            TEST_PASS(label, "%" PRIu64 " lines OK", (uint64_t) ((probes[i].end - probes[i].start) / LINE_BYTES));
        }
    }

    TEST_RESULT("Interleave marker test", f);
    return f;
}

/* =========================================================================
 * Suite 3: access width test
 *
 * Writes with every access width across a region spanning several
 * interleave lines (so both/all channels see narrow strobed writes),
 * then verifies bytewise.
 * ========================================================================= */

static int test_access_widths(const window_t *wins) {
    int f = 0;
    TEST_HDR("Access width test");

    uint64_t base = wins[0].start;      /* line-aligned by construction */
    const uint64_t span = 4 * LINE_BYTES;
    printf("  region: [0x%" PRIx64 ", 0x%" PRIx64 ") — spans %llu interleave lines\n",
           base, base + span, (unsigned long long) (span / LINE_BYTES));

    struct { const char *name; unsigned width; } cases[] = {
        {"8-bit stores", 1}, {"16-bit stores", 2}, {"32-bit stores", 4},
#if __riscv_xlen == 64
        {"64-bit stores", 8},
#endif
    };

    for (size_t c = 0; c < sizeof cases / sizeof cases[0]; c++) {
        unsigned wsz = cases[c].width;
        uint64_t seed = SEED_A + c + 1;

        for (uint64_t off = 0; off < span; off += wsz) {
            uint64_t a = base + off;
            uint64_t v = mix64(a ^ seed);
            switch (wsz) {
                case 1: *(volatile uint8_t *) (uintptr_t) a = (uint8_t) v; break;
                case 2: *(volatile uint16_t *) (uintptr_t) a = (uint16_t) v; break;
                case 4: *(volatile uint32_t *) (uintptr_t) a = (uint32_t) v; break;
                default: *(volatile uint64_t *) (uintptr_t) a = v; break;
            }
        }
        __asm__ volatile ("fence rw,rw" ::: "memory");
        cache_scrub(wins[0].start + WIN_BYTES / 2);

        uint64_t bad = 0, first_bad = 0;
        for (uint64_t off = 0; off < span; off += wsz) {
            uint64_t a = base + off;
            uint64_t exp = mix64(a ^ seed), got;
            switch (wsz) {
                case 1: got = *(volatile const uint8_t *) (uintptr_t) a;  exp = (uint8_t) exp; break;
                case 2: got = *(volatile const uint16_t *) (uintptr_t) a; exp = (uint16_t) exp; break;
                case 4: got = *(volatile const uint32_t *) (uintptr_t) a; exp = (uint32_t) exp; break;
                default: got = *(volatile const uint64_t *) (uintptr_t) a; break;
            }
            if (got != exp && bad++ == 0) first_bad = a;
        }
        if (bad) {
            TEST_FAIL(cases[c].name, "%" PRIu64 " bad, first @0x%" PRIx64, bad, first_bad);
            f++;
        } else {
            TEST_PASS(cases[c].name, "%llu accesses OK", (unsigned long long) (span / wsz));
        }
    }

    TEST_RESULT("Access width test", f);
    return f;
}

/* =========================================================================
 * Suite 4: burst copy test
 *
 * Large memcpy/memcmp between two line-crossing regions — exercises
 * back-to-back read and write bursts alternating between channels.
 * ========================================================================= */

static int test_burst_copy(const window_t *wins) {
    int f = 0;
    TEST_HDR("Burst copy test");

    const uint64_t blk = 1ull << 20;    /* 1 MiB */
    uint64_t src = wins[0].start;
    uint64_t dst = wins[0].start + WIN_BYTES / 2;
    printf("  src=0x%" PRIx64 "  dst=0x%" PRIx64 "  block=%llu KiB\n",
           src, dst, (unsigned long long) (blk >> 10));

    volatile word_t *ps = (volatile word_t *) (uintptr_t) src;
    for (uint64_t i = 0; i < blk / WORD_BYTES; i++)
        ps[i] = pat(src + i * WORD_BYTES, SEED_A ^ 0xB0B);
    __asm__ volatile ("fence rw,rw" ::: "memory");

    memcpy((void *) (uintptr_t) dst, (const void *) (uintptr_t) src, blk);
    __asm__ volatile ("fence rw,rw" ::: "memory");
    cache_scrub(wins[0].start + WIN_BYTES / 4);

    if (memcmp((const void *) (uintptr_t) dst, (const void *) (uintptr_t) src, blk) != 0) {
        TEST_FAIL("memcpy 1 MiB", "dst != src after copy");
        f++;
    } else {
        TEST_PASS("memcpy 1 MiB", "copy verified");
    }

    /* verify the source still matches the generator (copy must not clobber) */
    uint64_t bad = 0;
    for (uint64_t i = 0; i < blk / WORD_BYTES; i++)
        if (ps[i] != pat(src + i * WORD_BYTES, SEED_A ^ 0xB0B)) bad++;
    if (bad) {
        TEST_FAIL("src integrity", "%" PRIu64 " words clobbered", bad);
        f++;
    } else {
        TEST_PASS("src integrity", "source unchanged");
    }

    TEST_RESULT("Burst copy test", f);
    return f;
}

/* -------------------------------------------------------------------------
 * Shared mismatch reporting for the full-range suites
 * ---------------------------------------------------------------------- */

static uint64_t sweep_failures;

static void report_bad(uint64_t a, word_t got, word_t exp) {
    if (sweep_failures < MAX_ERR_PRINT)
        printf("  [BAD ] 0x%" PRIx64 ": got 0x%" PRIxPTR " expected 0x%" PRIxPTR "\n",
               a, (uintptr_t) got, (uintptr_t) exp);
    else if (sweep_failures == MAX_ERR_PRINT)
        printf("  [BAD ] ... suppressing further mismatch prints\n");
    sweep_failures++;
}

/* =========================================================================
 * Suite 5: full-range line edge test
 *
 * One unique marker at the first word of EVERY 64 B interleave line of the
 * testable range; verification starts only after every line is written.
 * The deinterleaver only rewrites address bits >= log2(LINE_BYTES), so any
 * mapping bug aliases whole lines onto whole lines — line granularity gives
 * complete aliasing/hole coverage of the address space at a fraction of
 * the word-sweep cost (one hash + one access per 64 B instead of per 8 B).
 * ========================================================================= */

#define SEED_LINE  (SEED_A ^ UINT64_C(0xED6E))

static void line_pass(const window_t *w, bool verify) {
    uint64_t next_print = w->start + CHUNK_BYTES;
    uint64_t t0 = clock_now(), tc = t0;

    for (uint64_t a = w->start; a < w->end; a += LINE_BYTES) {
        volatile word_t *p = (volatile word_t *) (uintptr_t) a;
        word_t e = pat(a, SEED_LINE);
        if (verify) {
            word_t got = *p;
            if (got != e) report_bad(a, got, e);
        } else {
            *p = e;
        }
        if (a + LINE_BYTES >= next_print && a + LINE_BYTES < w->end) {
            uint64_t now = clock_now();
            printf("    ... 0x%" PRIx64 "  (%u MiB/s covered)\n", (uint64_t) (a + LINE_BYTES),
                   mibps(CHUNK_BYTES, now - tc));
            tc = now;
            next_print += CHUNK_BYTES;
        }
    }
    __asm__ volatile ("fence rw,rw" ::: "memory");

    uint64_t bytes = w->end - w->start;
    printf("    done: %" PRIu64 " MiB covered (%u MiB/s)\n",
           bytes >> 20, mibps(bytes, clock_now() - t0));
}

static int test_line_edges(const window_t *wins, size_t n_wins) {
    TEST_HDR("Full-range line edge test");
    sweep_failures = 0;

    uint64_t total = 0;
    for (size_t i = 0; i < n_wins; i++) total += wins[i].end - wins[i].start;
    printf("  one marker per %llu B line over %" PRIu64 " MiB\n",
           (unsigned long long) LINE_BYTES, total >> 20);

    printf("\n  phase 1/2: write line markers\n");
    for (size_t i = 0; i < n_wins; i++) {
        printf("    window [0x%" PRIx64 ", 0x%" PRIx64 ")\n", wins[i].start, wins[i].end);
        line_pass(&wins[i], false);
    }
    cache_scrub(wins[0].start);

    printf("\n  phase 2/2: verify line markers\n");
    for (size_t i = 0; i < n_wins; i++) {
        printf("    window [0x%" PRIx64 ", 0x%" PRIx64 ")\n", wins[i].start, wins[i].end);
        line_pass(&wins[i], true);
    }

    int f = sweep_failures > 0 ? 1 : 0;
    if (f)
        printf("\n  total mismatching lines: %" PRIu64 "\n", sweep_failures);
    TEST_RESULT("Full-range line edge test", f);
    return f;
}

/* =========================================================================
 * Suite 6: full-range moving-inversion sweep
 *
 * Sweep A: write pat(addr) to every word of every window.
 * Sweep B: verify pat(addr), write ~pat(addr)   (fused, per word)
 * Sweep C: verify ~pat(addr)
 *
 * Every bit of every testable byte is written and read in both polarities;
 * writes of the whole range complete before their verification, so any
 * aliasing across the whole address space is caught.
 * ========================================================================= */

typedef enum { SWEEP_WRITE, SWEEP_VERIFY_WRITE_INV, SWEEP_VERIFY_INV } sweep_mode_t;

static void sweep(const window_t *w, sweep_mode_t mode) {
    uint64_t next_print = w->start + CHUNK_BYTES;
    uint64_t t0 = clock_now(), tc = t0;

    for (uint64_t a = w->start; a < w->end; a += WORD_BYTES) {
        volatile word_t *p = (volatile word_t *) (uintptr_t) a;
        word_t e = pat(a, SEED_A);
        switch (mode) {
            case SWEEP_WRITE:
                *p = e;
                break;
            case SWEEP_VERIFY_WRITE_INV: {
                word_t got = *p;
                if (got != e) report_bad(a, got, e);
                *p = (word_t) ~e;
                break;
            }
            case SWEEP_VERIFY_INV: {
                word_t got = *p;
                if (got != (word_t) ~e) report_bad(a, got, (word_t) ~e);
                break;
            }
        }
        if (a + WORD_BYTES >= next_print && a + WORD_BYTES < w->end) {
            uint64_t now = clock_now();
            printf("    ... 0x%" PRIx64 "  (%u MiB/s)\n", a + WORD_BYTES,
                   mibps(CHUNK_BYTES * (mode == SWEEP_VERIFY_WRITE_INV ? 2 : 1), now - tc));
            tc = now;
            next_print += CHUNK_BYTES;
        }
    }
    __asm__ volatile ("fence rw,rw" ::: "memory");

    uint64_t bytes = w->end - w->start;
    uint64_t dt = clock_now() - t0;
    printf("    done: %" PRIu64 " MiB in window (%u MiB/s)\n",
           bytes >> 20, mibps(bytes * (mode == SWEEP_VERIFY_WRITE_INV ? 2 : 1), dt));
}

static int test_full_sweep(const window_t *wins, size_t n_wins) {
    TEST_HDR("Full-range moving-inversion sweep");
    sweep_failures = 0;

    uint64_t total = 0;
    for (size_t i = 0; i < n_wins; i++) total += wins[i].end - wins[i].start;
    printf("  testable: %" PRIu64 " MiB in %zu window(s)\n", total >> 20, n_wins);

    static const char *phase_name[] = {
        "write pattern", "verify + write inverted", "verify inverted"
    };
    for (int phase = 0; phase < 3; phase++) {
        printf("\n  phase %d/3: %s\n", phase + 1, phase_name[phase]);
        for (size_t i = 0; i < n_wins; i++) {
            printf("    window [0x%" PRIx64 ", 0x%" PRIx64 ")\n", wins[i].start, wins[i].end);
            sweep(&wins[i], (sweep_mode_t) phase);
        }
        cache_scrub(wins[0].start);
    }

    int f = sweep_failures > 0 ? 1 : 0;
    if (f)
        printf("\n  total mismatching words: %" PRIu64 "\n", sweep_failures);
    TEST_RESULT("Full-range sweep", f);
    return f;
}

/* =========================================================================
 * Entry point
 * ========================================================================= */

int main(void) {
    int total = 0;

    printf("\n================ SoCeteer DRAM test ================\n");

    ram_range_t ranges[MAX_RANGES];
    size_t n_ranges = dtb_ram_ranges(ranges, MAX_RANGES);
    if (n_ranges == 0) {
        TEST_FAIL("DTB memory node", "no /memory node with reg found");
        printf("=== TOTAL: 1 failure ===\n");
        return 1;
    }

    clock_init();

    printf("  harts: %d   wall clock: %s\n", dtb_nharts(), s_mtime_lo ? "CLINT mtime" : "unavailable");
    for (size_t i = 0; i < n_ranges; i++)
        printf("  RAM range %zu: base=0x%" PRIx64 " size=0x%" PRIx64 " (%" PRIu64 " MiB)\n",
               i, ranges[i].base, ranges[i].size, ranges[i].size >> 20);

    window_t wins[MAX_RANGES];
    uint64_t clamped = 0;
    size_t n_wins = compute_windows(ranges, n_ranges, wins, MAX_RANGES, &clamped);
    if (n_wins == 0) {
        TEST_FAIL("test window", "no testable memory outside program footprint");
        printf("=== TOTAL: 1 failure ===\n");
        return 1;
    }
    if (clamped)
        printf("  WARNING: %" PRIu64 " MiB above the %u-bit address limit not testable on this xlen\n",
               clamped >> 20, (unsigned) (8 * sizeof(void *)));
    for (size_t i = 0; i < n_wins; i++)
        printf("  window %zu: [0x%" PRIx64 ", 0x%" PRIx64 ") (%" PRIu64 " MiB)\n",
               i, wins[i].start, wins[i].end, (wins[i].end - wins[i].start) >> 20);
    printf("  (program footprint below 0x%" PRIx64 " is excluded)\n", wins[0].start);

    total += test_capacity_probe(wins, n_wins);
    if (total != 0) {
        /* The claimed range is not backed by distinct memory. The full-range
         * suites would write through the aliases - including onto this
         * program's own text and stack - and crash mid-run. Stop here. */
        printf("ABORTING: capacity check failed - the remaining suites would corrupt\n");
        printf("          the running program through the detected aliasing.\n");
        printf("=== TOTAL: %d failure%s ===\n", total, total == 1 ? "" : "s");
        return 1;
    }
    total += test_interleave_markers(wins, n_wins);
    total += test_access_widths(wins);
    total += test_burst_copy(wins);
    total += test_line_edges(wins, n_wins);
    total += test_full_sweep(wins, n_wins);

    printf("=== TOTAL: %d failure%s ===\n", total, total == 1 ? "" : "s");
    return total == 0 ? 0 : 1;
}
