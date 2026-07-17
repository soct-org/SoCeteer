# BOOT.ELF: OpenSBI fw_payload wrapping a Linux kernel (with a minimal embedded
# initramfs) and the design's device tree - loadable by the sd-boot bootrom from the
# SD card's FAT root. Requires drop-in source checkouts next to binaries/:
#   binaries/linux-stable/   the kernel tree
#   binaries/opensbi/        the OpenSBI tree (>= 1.1)
# Without them, these targets are skipped (status message below).
#
# Targets:
#   boot-init    the freestanding initramfs /init (repo's vendored bare-metal toolchain)
#   boot-dtb     the design DTS compiled with fixup padding (embedded via FW_FDT_PATH,
#                so BOOT.ELF does not depend on the bitstream's ROM DTB)
#   linux-image  the kernel Image (defconfig + linux.fragment.in)
#   boot-elf     the final ${SOCT_ELFS_DIR}/BOOT.ELF
#
# Toolchains: the kernel and OpenSBI are built with LLVM/Clang (their upstream
# `LLVM=<bindir>/` mode) - the vendored bare-metal binutils cannot link them (no
# -pie/-shared support: OpenSBI links -pie, and the kernel's vDSO is a shared object).
# LLVM is auto-detected cross-platform via shared/cmake/FindLLVMRiscv.cmake (PATH,
# Homebrew, Debian /usr/lib/llvm-*, MacPorts - each candidate proved by linking a
# RISC-V PIE through ld.lld; Apple's Xcode clang has no RISC-V backend and never
# qualifies). Override with -DSOCT_BOOT_LLVM_DIR=<bin dir>. Everything bare-metal
# (init, programs, bootroms) stays on the vendored toolchain.
#
# Host requirements, probed at configure and enforced with an explanation when the
# affected target is built (the rest of binaries/ is never blocked):
#  - LLVM with the RISC-V backend + ld.lld (macOS: `brew install llvm`;
#    Debian/Ubuntu: clang + lld) - needed by linux-image and boot-elf.
#  - GNU make >= 4.0 plus host flex/bison/bc for the kernel; auto-detected via
#    shared/cmake/FindGNUMake.cmake, override with -DSOCT_BOOT_MAKE=<path>.

include(ProcessorCount)

set(SOCT_BOOT_LINUX_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../linux-stable" CACHE PATH
        "Linux kernel source tree")
set(SOCT_BOOT_OPENSBI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../opensbi" CACHE PATH
        "OpenSBI source tree")

if (NOT EXISTS "${SOCT_BOOT_LINUX_DIR}/Makefile" OR NOT EXISTS "${SOCT_BOOT_OPENSBI_DIR}/Makefile")
    message(STATUS "boot: no kernel/OpenSBI trees at ${SOCT_BOOT_LINUX_DIR} / ${SOCT_BOOT_OPENSBI_DIR} - skipping the BOOT.ELF targets")
    return()
endif ()

if (NOT DEFINED SOCT_MEM_BASE_ADDR OR NOT DEFINED SOCT_ELFS_DIR OR NOT DEFINED SOCT_DTS)
    message(FATAL_ERROR "boot: the system file must define SOCT_MEM_BASE_ADDR, SOCT_ELFS_DIR and SOCT_DTS")
endif ()

ProcessorCount(_boot_ncpu)
if (_boot_ncpu EQUAL 0)
    set(_boot_ncpu 4)
endif ()

set(SOCT_BOOT_LLVM_DIR "" CACHE PATH
        "LLVM bin directory (clang, ld.lld, llvm-*) for the kernel and OpenSBI builds; empty = auto-detect via FindLLVMRiscv")
set(SOCT_BOOT_MAKE "" CACHE STRING
        "GNU make for the kernel and OpenSBI builds (kernel needs >= 4.0); empty = auto-detect via FindGNUMake")
set(SOCT_LINUX_CMDLINE "console=ttyUL0,115200 earlycon=uartlite,mmio,0x60010000" CACHE STRING
        "Kernel command line baked into the Image (the SoC DTB has no /chosen node)")
set(SOCT_LINUX_IMAGE "" CACHE FILEPATH
        "Prebuilt kernel Image to embed instead of building linux-image here")

# --- Configure-time capability probes (results reported, enforced per target) -------
# The kernel and OpenSBI builds cannot live under every path: OpenSBI passes its linker
# scripts as -Wl,-T<path>, and the compiler driver splits -Wl arguments at EVERY comma -
# a ',' anywhere in the build path shatters the linker command line. GNU make in turn
# cannot cope with ' ' in paths. Refuse such build directories up front.
if ("${CMAKE_CURRENT_BINARY_DIR}" MATCHES "[, ]")
    set(_path_ok FALSE)
    message(STATUS "boot: build directory '${CMAKE_CURRENT_BINARY_DIR}' contains ',' or ' ' - linux-image and boot-elf will refuse (kernel/OpenSBI makefiles cannot handle such paths); use a build directory without them, e.g. rename the IDE build profile")
else ()
    set(_path_ok TRUE)
endif ()

if (SOCT_BOOT_LLVM_DIR)
    # Explicit override: must be a complete directory (clang + ld.lld); still prove it, loudly.
    execute_process(
            COMMAND "${SOCT_BOOT_LLVM_DIR}/clang" --target=riscv64-unknown-elf -fPIE -nostdlib
            "-fuse-ld=${SOCT_BOOT_LLVM_DIR}/ld.lld" -Wl,-pie -x c /dev/null
            -o "${CMAKE_CURRENT_BINARY_DIR}/llvm-probe"
            RESULT_VARIABLE _llvm_probe OUTPUT_QUIET ERROR_QUIET)
    file(REMOVE "${CMAKE_CURRENT_BINARY_DIR}/llvm-probe")
    if (_llvm_probe EQUAL 0)
        set(_llvm_ok TRUE)
        set(_llvm_bindir "${SOCT_BOOT_LLVM_DIR}")
        set(_llvm_lld "${SOCT_BOOT_LLVM_DIR}/ld.lld")
    else ()
        set(_llvm_ok FALSE)
        message(STATUS "boot: SOCT_BOOT_LLVM_DIR='${SOCT_BOOT_LLVM_DIR}' cannot link a RISC-V PIE through its ld.lld - linux-image and boot-elf will refuse")
    endif ()
else ()
    find_package(LLVMRiscv QUIET)
    if (LLVMRiscv_FOUND)
        set(_llvm_ok TRUE)
        set(_llvm_bindir "${LLVMRISCV_BIN_DIR}")
        set(_llvm_lld "${LLVMRISCV_LLD}")
        message(STATUS "boot: using LLVM/Clang ${LLVMRISCV_VERSION} at ${LLVMRISCV_BIN_DIR}")
    else ()
        set(_llvm_ok FALSE)
        message(STATUS "boot: no RISC-V-capable LLVM found (clang + ld.lld) - linux-image and boot-elf will refuse; install one (macOS: brew install llvm lld; Debian: clang + lld) or set -DSOCT_BOOT_LLVM_DIR")
    endif ()
endif ()

if (SOCT_BOOT_MAKE)
    # Explicit override: still verify it, loudly.
    set(_boot_make "${SOCT_BOOT_MAKE}")
    execute_process(COMMAND ${_boot_make} --version
            OUTPUT_VARIABLE _make_ver ERROR_QUIET OUTPUT_STRIP_TRAILING_WHITESPACE)
    if (_make_ver MATCHES "GNU Make ([0-9]+)\\.([0-9]+)" AND NOT CMAKE_MATCH_1 LESS 4)
        set(_make_ok TRUE)
    else ()
        set(_make_ok FALSE)
        message(STATUS "boot: SOCT_BOOT_MAKE='${_boot_make}' is not GNU make >= 4.0 (kernel requirement) - linux-image will refuse")
    endif ()
else ()
    # Auto-detect: gmake/gnumake/make from PATH plus Homebrew/MacPorts locations,
    # each candidate version-checked (see shared/cmake/FindGNUMake.cmake).
    find_package(GNUMake 4.0 QUIET)
    if (GNUMake_FOUND)
        set(_boot_make "${GNUMAKE_EXECUTABLE}")
        set(_make_ok TRUE)
        message(STATUS "boot: using GNU make ${GNUMAKE_VERSION} at ${GNUMAKE_EXECUTABLE}")
    else ()
        set(_boot_make "make") # good enough for OpenSBI; the kernel target stays gated
        set(_make_ok FALSE)
        message(STATUS "boot: no GNU make >= 4.0 found (kernel requirement) - linux-image will refuse; install one (macOS: brew install make) or set -DSOCT_BOOT_MAKE=<path>")
    endif ()
endif ()

# A target that exists but explains exactly why it cannot run on this host.
function(soct_boot_blocked name reason remedy)
    add_custom_target(${name}
            COMMAND ${CMAKE_COMMAND} -E echo "${name}: ${reason}"
            COMMAND ${CMAKE_COMMAND} -E echo "${name}: ${remedy}"
            COMMAND ${CMAKE_COMMAND} -E false
            COMMENT "${name} is unavailable on this host"
            VERBATIM)
endfunction()

# --- /init (vendored toolchain, freestanding - newlib cannot target Linux) ----------
set(SOCT_BOOT_INIT "${CMAKE_CURRENT_BINARY_DIR}/init")
add_custom_command(OUTPUT "${SOCT_BOOT_INIT}"
        COMMAND ${CMAKE_C_COMPILER} -march=${SOCT_ARCH} -mabi=${SOCT_ABI}
        -static -nostdlib -ffreestanding -O2 -Wall -Wextra
        -o "${SOCT_BOOT_INIT}" "${CMAKE_CURRENT_SOURCE_DIR}/init.c"
        DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/init.c"
        COMMENT "Building initramfs /init (freestanding)"
        VERBATIM)
add_custom_target(boot-init DEPENDS "${SOCT_BOOT_INIT}")

set(SOCT_BOOT_INITRAMFS_DESC "${CMAKE_CURRENT_BINARY_DIR}/initramfs.desc")
configure_file(initramfs.desc.in "${SOCT_BOOT_INITRAMFS_DESC}" @ONLY)
configure_file(linux.fragment.in "${CMAKE_CURRENT_BINARY_DIR}/linux.fragment" @ONLY)

# --- Device tree with fixup padding (OpenSBI patches it in place) --------------------
set(_boot_dtb "${CMAKE_CURRENT_BINARY_DIR}/boot.dtb")
add_custom_command(OUTPUT "${_boot_dtb}"
        COMMAND ${DTC} -I dts -O dtb -p 4096 -o "${_boot_dtb}" "${SOCT_DTS}"
        DEPENDS "${SOCT_DTS}"
        COMMENT "Building padded boot DTB from ${SOCT_DTS}"
        VERBATIM)
add_custom_target(boot-dtb DEPENDS "${_boot_dtb}")

# --- Firmware addresses, derived from the system file --------------------------------
math(EXPR _fw_text_start "${SOCT_MEM_BASE_ADDR}" OUTPUT_FORMAT HEXADECIMAL)
# The relocated-DTB address: clear of the firmware (base..+2 MiB) and any plausible
# kernel Image + embedded initramfs (payload at base + 2 MiB).
math(EXPR _fw_fdt_addr "${SOCT_MEM_BASE_ADDR} + 128 * 1024 * 1024" OUTPUT_FORMAT HEXADECIMAL)

# --- Kernel Image --------------------------------------------------------------------
set(_kbuild "${CMAKE_CURRENT_BINARY_DIR}/linux")
# macOS ships no <elf.h>; the kernel's host tools (scripts/elf-parse.h users) need one.
# host-compat/ carries musl's - injected for host tools only, never target code.
# -D_UUID_T -D__GETHOSTUUID_H: suppress the macOS SDK's uuid_t (an unsigned char[16]
# typedef pulled in via unistd.h) and the gethostuuid() declaration that needs it -
# scripts/mod/file2alias.c defines the kernel's struct uuid_t itself and the two collide.
# NB: this project cross-compiles (CMAKE_SYSTEM_NAME=Generic), so the check must be
# CMAKE_HOST_APPLE - plain APPLE describes the target and is always false here.
# darwin-compat.h is force-included on top: O_LARGEFILE and copy_file_range for
# usr/gen_init_cpio.c. And the kernel's generator scripts assume GNU sed (BSD sed
# renders a replacement \t as a literal 't', which mangles every macro name in the
# vDSO offsets header into e.g. __vdso_rt_sigreturn_offsett0x800) - so on macOS a
# PATH shim mapping sed -> gsed is prepended for the kernel invocations only.
set(_khostflags "")
set(_kenv "")
set(_darwin_sed_ok TRUE)
if (CMAKE_HOST_APPLE)
    set(_khostflags "HOSTCFLAGS=-I${CMAKE_CURRENT_SOURCE_DIR}/host-compat -include ${CMAKE_CURRENT_SOURCE_DIR}/host-compat/darwin-compat.h -D_UUID_T -D__GETHOSTUUID_H")
    find_program(_SOCT_BOOT_GSED NAMES gsed PATHS /opt/homebrew/bin /usr/local/bin /opt/local/bin)
    mark_as_advanced(_SOCT_BOOT_GSED)
    if (_SOCT_BOOT_GSED)
        set(_hostbin "${CMAKE_CURRENT_BINARY_DIR}/hostbin")
        file(MAKE_DIRECTORY "${_hostbin}")
        file(CREATE_LINK "${_SOCT_BOOT_GSED}" "${_hostbin}/sed" SYMBOLIC)
        set(_kenv ${CMAKE_COMMAND} -E env "PATH=${_hostbin}:$ENV{PATH}")
    else ()
        set(_darwin_sed_ok FALSE)
        message(STATUS "boot: no GNU sed found (kernel generator scripts need it on macOS) - linux-image will refuse; brew install gnu-sed")
    endif ()
endif ()
# With LLVM=<dir>/, kbuild takes every tool from that directory; CROSS_COMPILE supplies
# only the clang --target triple - no binaries of that name are needed or used.
set(_kllvm "LLVM=${_llvm_bindir}/" "CROSS_COMPILE=riscv64-linux-gnu-")

if (SOCT_LINUX_IMAGE)
    set(_image "${SOCT_LINUX_IMAGE}")
    add_custom_target(linux-image
            COMMAND ${CMAKE_COMMAND} -E echo "linux-image: using prebuilt ${SOCT_LINUX_IMAGE}"
            VERBATIM)
elseif (NOT _path_ok)
    set(_image "${_kbuild}/arch/riscv/boot/Image")
    soct_boot_blocked(linux-image
            "the build directory path contains ',' or ' ', which the kernel/OpenSBI makefiles cannot handle"
            "use a build directory without those characters (e.g. rename the IDE build profile)")
elseif (_make_ok AND _llvm_ok AND _darwin_sed_ok)
    set(_image "${_kbuild}/arch/riscv/boot/Image")
    add_custom_command(OUTPUT "${_kbuild}/.config"
            # O= builds refuse to start while an in-tree .config exists - fail with the fix
            COMMAND sh -c "test ! -f '${SOCT_BOOT_LINUX_DIR}/.config' || { echo 'boot: in-tree .config found; run: make -C ${SOCT_BOOT_LINUX_DIR} mrproper' >&2; exit 1; }"
            COMMAND ${_kenv} ${_boot_make} -C "${SOCT_BOOT_LINUX_DIR}" ARCH=riscv
            ${_kllvm} O=${_kbuild} ${_khostflags} defconfig
            COMMAND sh -c "cat '${CMAKE_CURRENT_BINARY_DIR}/linux.fragment' >> '${_kbuild}/.config'"
            COMMAND ${_kenv} ${_boot_make} -C "${SOCT_BOOT_LINUX_DIR}" ARCH=riscv
            ${_kllvm} O=${_kbuild} ${_khostflags} olddefconfig
            DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/linux.fragment"
            COMMENT "Configuring kernel (defconfig + linux.fragment)"
            VERBATIM)
    add_custom_target(linux-image
            COMMAND ${_kenv} ${_boot_make} -C "${SOCT_BOOT_LINUX_DIR}" ARCH=riscv
            ${_kllvm} O=${_kbuild} ${_khostflags} -j${_boot_ncpu} Image
            DEPENDS "${_kbuild}/.config" "${SOCT_BOOT_INIT}" "${SOCT_BOOT_INITRAMFS_DESC}"
            COMMENT "Building kernel Image"
            VERBATIM)
elseif (NOT _darwin_sed_ok)
    set(_image "${_kbuild}/arch/riscv/boot/Image")
    soct_boot_blocked(linux-image
            "the kernel's generator scripts need GNU sed and none was found (BSD sed mangles the vDSO offsets header)"
            "brew install gnu-sed and reconfigure - it is then found automatically")
elseif (NOT _make_ok)
    set(_image "${_kbuild}/arch/riscv/boot/Image")
    soct_boot_blocked(linux-image
            "the kernel requires GNU make >= 4.0 and none was found (macOS ships 3.81)"
            "install one (macOS: brew install make) and reconfigure - it is then found automatically - or set -DSOCT_BOOT_MAKE=<path>, or build on a Linux host, or pass a prebuilt Image via -DSOCT_LINUX_IMAGE=<path>")
else ()
    set(_image "${_kbuild}/arch/riscv/boot/Image")
    soct_boot_blocked(linux-image
            "no RISC-V-capable LLVM toolchain found (the kernel's vDSO needs clang + ld.lld; the vendored bare-metal ld cannot link shared objects)"
            "install LLVM (macOS: brew install llvm; Debian: clang + lld) and reconfigure, or set -DSOCT_BOOT_LLVM_DIR=<bin dir>, or pass a prebuilt Image via -DSOCT_LINUX_IMAGE=<path>")
endif ()

# --- BOOT.ELF: OpenSBI fw_payload wrapping the Image + DTB ---------------------------
if (NOT _path_ok)
    soct_boot_blocked(boot-elf
            "the build directory path contains ',' or ' ' - OpenSBI passes linker scripts as -Wl,-T<path>, and the driver splits -Wl arguments at every comma"
            "use a build directory without those characters (e.g. rename the IDE build profile)")
elseif (_llvm_ok)
    set(_sbibuild "${CMAKE_CURRENT_BINARY_DIR}/opensbi")
    set(_boot_elf "${SOCT_ELFS_DIR}/BOOT.ELF")
    add_custom_target(boot-elf
            COMMAND ${CMAKE_COMMAND} -E make_directory "${_sbibuild}"
            # OpenSBI does not rebuild the embedded payload/FDT objects when their
            # source files change; drop them so BOOT.ELF always embeds current bits.
            # The .bin must go too: it is the final make target and the .elf only an
            # intermediate - with the .bin in place, make declares everything current.
            COMMAND ${CMAKE_COMMAND} -E rm -f
            "${_sbibuild}/platform/generic/firmware/fw_payload.o"
            "${_sbibuild}/platform/generic/firmware/fw_payload.elf"
            "${_sbibuild}/platform/generic/firmware/fw_payload.bin"
            # USE_LD_FLAG with an absolute -fuse-ld: OpenSBI links its firmware through
            # the clang driver, whose linker search would otherwise have to find ld.lld
            # on its own (it cannot when Homebrew splits llvm and lld into separate kegs).
            COMMAND ${_boot_make} -C "${SOCT_BOOT_OPENSBI_DIR}" O=${_sbibuild}
            LLVM=${_llvm_bindir}/ "USE_LD_FLAG=-fuse-ld=${_llvm_lld}"
            PLATFORM=generic PLATFORM_RISCV_XLEN=64 READLINK=readlink
            FW_PIC=n FW_TEXT_START=${_fw_text_start} FW_JUMP=n FW_DYNAMIC=n
            FW_PAYLOAD=y FW_PAYLOAD_OFFSET=0x200000 FW_PAYLOAD_PATH=${_image}
            FW_FDT_PATH=${_boot_dtb} FW_PAYLOAD_FDT_ADDR=${_fw_fdt_addr} -j${_boot_ncpu}
            COMMAND ${CMAKE_COMMAND} -E copy
            "${_sbibuild}/platform/generic/firmware/fw_payload.elf" "${_boot_elf}"
            COMMAND ${CMAKE_COMMAND} -E echo "boot-elf: ${_boot_elf} - copy to the SD card FAT root"
            DEPENDS linux-image boot-dtb
            COMMENT "Building BOOT.ELF (OpenSBI fw_payload + kernel + DTB)"
            VERBATIM)
else ()
    soct_boot_blocked(boot-elf
            "no RISC-V-capable LLVM toolchain found (OpenSBI links with -pie; the vendored bare-metal ld cannot)"
            "install LLVM (macOS: brew install llvm; Debian: clang + lld) and reconfigure, or set -DSOCT_BOOT_LLVM_DIR=<bin dir>")
endif ()
