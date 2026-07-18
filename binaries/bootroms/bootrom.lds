/* Shared linker script for every boot ROM under bootroms/. The layout is a contract
 * between the hardware, the ROM contents and the flash tooling:
 *
 *   SOCT_BOOTROM_BASE_ADDR  the ROM base - `_start` (all-harts entry) lives here
 *   SOCT_BOOTROM_HANG_ADDR  the hart reset vector - `.text.hang` sits exactly here
 *   SOCT_DTB_ADDR           the embedded device tree - `.rodata.dtb` sits exactly
 *                           here, and JTAG flashing passes this address in a1
 *
 * All three arrive as linker symbols, promoted from the system file's
 * SOCT_COMPILE_DEFS by binaries/CMakeLists.txt - no address is spelled here, and the
 * generator that sizes the hardware ROM is the single source. Code and read-only data
 * follow the DTB. A ROM cannot be written, so .data must be empty; .bss (for ROMs
 * that need working memory, like sd-boot) lives in a small scratch region at the
 * start of DRAM. A bootrom can override this script by setting SOCT_LD_SCRIPT before
 * including bootroms/template.cmake. */

OUTPUT_ARCH("riscv")
ENTRY(_start)

ASSERT(DEFINED(SOCT_MEM_BASE_ADDR), "SOCT_MEM_BASE_ADDR must be defined (promoted from SOCT_COMPILE_DEFS)");
ASSERT(DEFINED(SOCT_BOOTROM_BASE_ADDR), "SOCT_BOOTROM_BASE_ADDR must be defined - regenerate the system file (older SOCTSystem.cmake files predate it)");
ASSERT(DEFINED(SOCT_BOOTROM_HANG_ADDR), "SOCT_BOOTROM_HANG_ADDR must be defined - regenerate the system file (older SOCTSystem.cmake files predate it)");
ASSERT(DEFINED(SOCT_DTB_ADDR), "SOCT_DTB_ADDR must be defined in SOCT_COMPILE_DEFS - regenerate the system file");

MEMORY {
  maskrom_mem (rx) : ORIGIN = SOCT_BOOTROM_BASE_ADDR, LENGTH = 0x00010000
  memory_mem (rwx) : ORIGIN = SOCT_MEM_BASE_ADDR,
                     LENGTH = DEFINED(BOOTROM_SCRATCH_SIZE) ? BOOTROM_SCRATCH_SIZE : 0x8000
}

PHDRS {
  text PT_LOAD;
}

SECTIONS {
  PROVIDE(_ram = ORIGIN(memory_mem));

  .text SOCT_BOOTROM_BASE_ADDR : {
    *(.text.start)
    /* The reset stub gets exactly the room up to the reset vector; ld refuses to move
     * the location counter backwards, so an overgrown stub fails the link loudly. */
    . = SOCT_BOOTROM_HANG_ADDR - SOCT_BOOTROM_BASE_ADDR;
    *(.text.hang)
    . = SOCT_DTB_ADDR - SOCT_BOOTROM_BASE_ADDR;
    *(.rodata.dtb)
    *(.text.unlikely .text.unlikely.*)
    *(.text .text.* .gnu.linkonce.t.*)
    *(.rodata .rodata.* .gnu.linkonce.r.*)
    *(.srodata .srodata.*)
  } >maskrom_mem  :text
  ASSERT(_dtb == SOCT_DTB_ADDR, "the embedded DTB is not at SOCT_DTB_ADDR - the hang stub outgrew its slot")
  ASSERT(SIZEOF(.text) <= LENGTH(maskrom_mem), "BootROM .text section exceeds the ROM size")

  .data (INFO) : {
    /* This section must be empty */
    *(.data .data.* .gnu.linkonce.d.*)
    *(.sdata .sdata.* .gnu.linkonce.s.*)
    *(.tohost)
  }
  ASSERT(SIZEOF(.data) == 0, "BootROM .data section must be empty")

  .bss ORIGIN(memory_mem) (NOLOAD) : {
    . += 8; /* Trap vector */
    PROVIDE(_fbss = .);
    PROVIDE(__global_pointer$ = . + 0x7C0);
    *(.sbss .sbss.* .gnu.linkonce.sb.*)
    *(.bss .bss.* .gnu.linkonce.b.*)
    . = ALIGN(8);
    PROVIDE(_ebss = .);
  } >memory_mem
  ASSERT(SIZEOF(.bss) <= 0x4000, "BootROM .bss section must be less than 0x4000 bytes")
  /* Ensure at least 8KB of stack between BSS end and the end of the scratch region */
  ASSERT(_ebss + 0x2000 <= ORIGIN(memory_mem) + LENGTH(memory_mem), "BootROM stack overflow: BSS too large for the scratch region")

  .note (INFO) : {
    *(.note .note.*)
    *(.eh_frame)
  }
}
