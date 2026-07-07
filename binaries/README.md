# Binaries

This directory contains RISC-V bare-metal programs and boot ROMs that run on the SoCeteer-generated SoC. All binaries share a common build infrastructure provided by `soctglue`.

## Directory layout

```
binaries/
  CMakeLists.txt           # Top-level build file
  programs/
    template.cmake         # Reusable CMake template for programs
    hello-hart/            # Example: multi-hart hello-world
    debug-sd/              # Example: SD card read/write test
    gemmini-basic/         # Example: Gemmini accelerator smoke test
    syscall-test/          # Example: syscall layer smoke test
  bootroms/
    template.cmake         # Reusable CMake template for boot ROMs
    testchipip-boot/       # Minimal boot ROM (testchipip-compatible)
    sd-boot/               # SD card boot ROM with FatFs
shared/
  soctglue/                # Runtime library linked into every program
    CMakeLists.txt
    soct.ld                # Linker script for programs
    include/soct/          # Public headers
    src/                   # crt0, stdio wrappers, syscall layer, FatFs glue
```

## soctglue

`soctglue` is a static C library (`libsoctglue.a`) that every program links against. It provides the baremetal runtime that sits between the hardware and your `main()`.

### What soctglue does

**Startup (`crt0.S` → `_soct_start_main`)**

The assembly entry point `_start` (placed in `.text.init`) runs on every hart. It:
1. Zeros all registers except `a0` (hart ID) and `a1` (DTB pointer), which the SoC passes at reset.
2. Initializes the FPU if the ISA extension is present.
3. Sets up the global pointer, thread pointer, and per-hart stack (each hart gets its own stack slice starting at `0x80000000`, sized by `__stack_shift` from the linker script).
4. On the primary hart (hart 0 by default), zeros BSS and calls global constructors.
5. Secondary harts spin on a boot barrier until the primary releases them.
6. Calls `_soct_start_main(hartid, dtb_ptr)` on the primary hart.

**`_soct_start_main`**

Before calling `main()`, this function:
1. Parses the DTB blob using the embedded `smoldtb` parser to discover peripherals.
2. If a UART is found in the DTB, registers a UART syscall handler so `printf` works over serial.
3. If an SD controller is found and `SOCT_NEEDS_FATFS` is set, mounts the SD volume via FatFs.
4. If the HTIF device is present (Verilator simulation), registers an HTIF syscall handler and fetches `argc`/`argv` from the host.
5. Releases secondary harts, then calls `main()`.

Secondary harts jump to `__main()` if your program defines it, or spin in `wfi` otherwise.

**Syscall handler chain**

Standard C library calls (`fopen`, `printf`, `read`, …) are redirected through a handler table. The linker wraps `puts`, `printf`, `vprintf`, `sprintf`, `snprintf`, `fopen`, `fopen64`, `freopen`, and `freopen64` so every I/O call passes through soctglue's dispatch instead of the newlib stubs.

The dispatch chain has two priority levels:
- **User handlers** — registered by program code with `soct_register_handler()`. Tried first.
- **Default handlers** — registered by soctglue internals (UART, HTIF) with `soct_register_default_handler()`. Tried only if every user handler passes.

Each handler inspects the syscall number and arguments and either handles the call (returning `SOCT_HANDLER_HANDLED`) or passes it on (`SOCT_HANDLER_PASS`). If no handler claims the syscall, `errno` is set to `ENOSYS`.

```c
// Registering a custom handler from main():
static void handle_exit(soct_handler_resp_t *resp, sc_type_t syscall, ...) {
    if (syscall == SOCT_EXIT)
        printf("Exiting with code %llu\n", a0);
    resp->status = SOCT_HANDLER_PASS; // let default handler actually exit
}

soct_register_handler((soct_handler_t){ .handle = handle_exit });
```

**FatFs integration (`soct_ff`)**

When `SOCT_NEEDS_FATFS` is defined, soctglue compiles in `soct_ff` — a glue layer that bridges FatFs with the standard `fopen`/`fread`/`fwrite`/`fclose` API. Programs access the SD card using ordinary POSIX file calls:

```c
FILE *f = fopen(SOCT_SD_PATH "/data.bin", "r");
fread(buf, 1, sizeof(buf), f);
fclose(f);
```

Without `SOCT_NEEDS_FATFS`, a stub is compiled instead that returns `ENOSYS` for all file operations.

**Helper API**

| Function | Description |
|---|---|
| `soct_hart_id()` | Returns the current hart's MHARTID |
| `soct_n_harts()` | Returns the number of CPU harts from the DTB, or −1 if unavailable |
| `soct_syscall(nr, a0…a6)` | Make a raw syscall through the handler chain |
| `soct_register_handler(h)` | Register a high-priority user handler |
| `soct_register_default_handler(h)` | Register a low-priority default handler |

### Linker script (`soct.ld`)

Programs are linked at `0x80000000` (DRAM base). The script:
- Places `.text.init` first so `_start` is at the load address.
- Reserves a `.htif` section (64-byte aligned) for the HTIF `tohost`/`fromhost` slots.
- Allocates a 128 KiB heap above BSS.
- Computes per-hart stack size as the next power of two above `24 KiB + TLS size`, then allocates contiguous stack regions for up to `SOCT_MAX_HARTS` (64) harts.

You can override the linker script by setting `SOCT_LD_SCRIPT` before including the program template.

---

## The system file (`SOCT_SYSTEM`)

Every build requires a **system file** — a CMake file generated by SoCeteer that describes the target SoC. It provides variables such as:

| Variable | Meaning |
|---|---|
| `SOCETEER_ROOT` | Absolute path to the SoCeteer checkout |
| `SOCETEER_VERSION` | Version string checked by soctglue |
| `SOCT_ARCH` / `SOCT_ABI` | RISC-V march/mabi flags (e.g. `rv64gc` / `lp64d`) |
| `SOCT_TARGET` | Build target (`verilator`, `fpga`, …) |
| `SOCT_COMPILE_DEFS` | Extra compiler definitions from the SoC config |
| `SOCT_DTS` / `SOCT_DTB` | Path to the device-tree source/blob |
| `SOCT_ELFS_DIR` | Output directory for ELF files |
| `SOCT_BOOTROM_IMG` | Output path for the boot ROM binary image |
| `SOCT_NCPUS` | Number of CPU cores in the SoC |
| `SOCT_CLINT_BASE` | Base address of the CLINT |

The top-level `CMakeLists.txt` finds this file via `FindSOCTSystem.cmake`. It looks first at the `SOCT_SYSTEM` variable (passed with `-DSOCT_SYSTEM=...`), then falls back to a `SOCTSystem-latest.cmake` symlink in the repository root that SoCeteer creates automatically.

---

## Adding a program

1. Create a subdirectory under `binaries/programs/`:

   ```
   binaries/programs/my-program/
     CMakeLists.txt
     main.c
   ```

2. Write a `CMakeLists.txt` that lists sources, then includes the template:

   ```cmake
   cmake_minimum_required(VERSION 3.20)

   list(APPEND CMAKE_C_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.c)
   # list(APPEND CMAKE_CXX_SRCS ...) for C++ sources

   include(${CMAKE_CURRENT_LIST_DIR}/../template.cmake)

   # Optional: add per-program flags after the include
   target_compile_options(${SOCT_PROGRAM} PRIVATE -std=gnu11 -O2)
   ```

3. Write `main.c`. The entry point is the standard `main(int argc, char **argv, char **envp)`. If the program uses multiple harts, also define `__main(...)` for secondary harts (same signature).

The top-level `CMakeLists.txt` automatically discovers every subdirectory under `programs/` and `bootroms/`, so no manual registration is needed.

### C++ programs

List sources in `CMAKE_CXX_SRCS` instead of (or in addition to) `CMAKE_C_SRCS`. The template detects the presence of C++ sources and links `supc++`/`stdc++` automatically. Exceptions and RTTI are disabled.

### Conditionally building for a specific target

```cmake
if (SOCT_TARGET STREQUAL "verilator")
    include(${CMAKE_CURRENT_LIST_DIR}/../template.cmake)
    # ...
endif ()
```

### Using the SD card / FatFs

Define `SOCT_NEEDS_FATFS` before the build (or in your program's CMakeLists.txt via `target_compile_definitions`) and access the SD volume with standard `fopen`/`fread`/`fwrite`. The volume root is `SOCT_SD_PATH` (defined to `/SD` by default with Linux-style volume IDs).

---

## Adding a boot ROM

Boot ROMs live under `binaries/bootroms/` and follow the same auto-discovery pattern. Their `CMakeLists.txt` includes `bootroms/template.cmake` instead:

```cmake
cmake_minimum_required(VERSION 3.20)

list(APPEND CMAKE_C_SRCS   ${CMAKE_CURRENT_SOURCE_DIR}/bootrom.c)
list(APPEND CMAKE_ASM_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/head.S)

include(${CMAKE_CURRENT_LIST_DIR}/../template.cmake)
```

The bootrom template:
- Compiles the ELF with `-Os -fno-pic`.
- Strips the ELF to a raw binary image with `objcopy -O binary --change-addresses=-0x10000`, placing the result at `SOCT_BOOTROM_IMG`.
- Passes `-DDEVICE_TREE=\"${SOCT_DTB}\"` so the source can embed the DTB at compile time.

Boot ROMs do **not** link against soctglue — they are self-contained.

---

## Building

```sh
cmake -S binaries -B build \
  -DSOCT_SYSTEM=/path/to/SOCTSystem.cmake \
  -DCMAKE_TOOLCHAIN_FILE=shared/cmake/toolchain-riscv.cmake
cmake --build build -j$(nproc)
```

ELF files land in `SOCT_ELFS_DIR` (set by the system file). Each program also gets an `.objdump` and `.nm` file generated alongside its ELF for inspection.

---

## Flashing to an FPGA

Every program gets a `<name>-flash` target that loads the compiled ELF onto an FPGA over JTAG using Xilinx `xsdb`. The target is only created when at least one of `SOCT_FLASH_XSDB` or `SOCT_FLASH_HOST` is set.

```sh
cmake --build build --target hello-hart-flash
```

### CMake variables

| Variable | Default | Description |
|---|---|---|
| `SOCT_FLASH_XSDB` | *(empty)* | Path to the `xsdb` binary (local, or on the remote host) |
| `SOCT_FLASH_HOST` | *(empty)* | SSH host to flash through — leave empty to flash locally |
| `SOCT_FLASH_REMOTE_DIR` | `/tmp` | Directory on the remote host to upload the ELF into |
| `SOCT_FLASH_BOOT_HART` | `0` | Boot hart index written into register `a0` before `con` |
| `SOCT_FLASH_BOOTROM_DTB_ADDR` | `0x00010080` | DTB base address written into register `a1` before `con` |

Pass any of these at configure time:

```sh
cmake -S binaries -B build \
  -DSOCT_SYSTEM=/path/to/SOCTSystem.cmake \
  -DSOCT_FLASH_XSDB=/opt/Xilinx/Vivado/2024.2/bin/xsdb \
  -DSOCT_FLASH_BOOT_HART=0 \
  -DSOCT_FLASH_BOOTROM_DTB_ADDR=0x00010080
```
