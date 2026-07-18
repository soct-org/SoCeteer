# BOOT.ELF: OpenSBI fw_payload wrapping a Linux kernel (with a minimal embedded
# initramfs) and the design's device tree - loadable by the sd-boot bootrom from the
# SD card's FAT root. Requires drop-in source checkouts next to binaries/:
#   binaries/linux-stable/   the kernel tree
#   binaries/opensbi/        the OpenSBI tree (>= 1.1)
# Without them, these targets are skipped (status message below).
#
# Targets:
#   boot-dtb     the design DTS compiled with fixup padding (embedded via FW_FDT_PATH,
#                so BOOT.ELF does not depend on the bitstream's ROM DTB)
#   linux-image  the kernel Image (defconfig + linux.fragment.in); its initramfs
#                carries programs/linux-init as /init (a Linux userspace program
#                built against the sysroot from binaries/linux-sysroot)
#   boot-elf     the final ${SOCT_ELFS_DIR}/BOOT.ELF
#
# Toolchains: the kernel and OpenSBI are built with LLVM/Clang (their upstream
# `LLVM=<bindir>/` mode) - the vendored bare-metal binutils cannot link them (no
# -pie/-shared support: OpenSBI links -pie, and the kernel's vDSO is a shared object).
# Host-tool discovery, the per-host requirements and the Darwin accommodations live in
# shared/cmake/SoctBootHostTools.cmake; every unmet requirement is reported at
# configure time and enforced with its remedy when the affected target is built - the
# rest of binaries/ is never blocked.

include(ProcessorCount)
include(SoctBootHostTools)

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

set(SOCT_LINUX_IMAGE "" CACHE FILEPATH
        "Prebuilt kernel Image to embed instead of building linux-image here")

soct_boot_find_host_tools()

# --- The initramfs /init: the linux-init userspace program ---------------------------
set(SOCT_BOOT_INIT "${SOCT_ELFS_DIR}/linux-init")
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
elseif (NOT SOCT_LINUX_USERSPACE_OK)
    set(_image "${_kbuild}/arch/riscv/boot/Image")
    soct_boot_blocked(linux-image
            "the initramfs needs the linux-init userspace program, and the linux-sysroot build is unavailable"
            "see the linux-sysroot messages at configure time")
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
            DEPENDS "${_kbuild}/.config" "${SOCT_BOOT_INITRAMFS_DESC}"
            COMMENT "Building kernel Image"
            VERBATIM)
    add_dependencies(linux-image linux-init)
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
            "install LLVM (macOS: brew install llvm lld; Debian: clang + lld) and reconfigure, or set -DSOCT_BOOT_LLVM_DIR=<bin dir>, or pass a prebuilt Image via -DSOCT_LINUX_IMAGE=<path>")
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
            LLVM=${_llvm_bindir}/ "USE_LD_FLAG=--ld-path=${_llvm_lld}"
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
            "install LLVM (macOS: brew install llvm lld; Debian: clang + lld) and reconfigure, or set -DSOCT_BOOT_LLVM_DIR=<bin dir>")
endif ()
