cmake_minimum_required(VERSION 3.21)

################################################################################################
# The Linux world of SoCeteer binaries - a SEPARATE CMake project from binaries/, because
# CMake allows one toolchain per build tree and the bare-metal xpack toolchain cannot
# target Linux. This project compiles with LLVM/Clang against a musl sysroot (see
# shared/cmake/toolchain-riscv-linux-musl.cmake) and owns everything that runs under or
# boots the kernel:
#   - the riscv64-linux-musl sysroot (kernel UAPI headers + static musl), built on demand
#     into shared/vendor/ so every build tree and IDE profile shares one copy
#   - the prebuilt static BusyBox for the shell boot image (hash-pinned Debian package,
#     fetched into shared/vendor/ once)
#   - userspace programs under userspace/ (auto-discovered; plain add_executable targets,
#     so IDEs index them - include userspace/userspace.cmake or userspace/initram.cmake)
#   - reserved for the future: drivers/ (out-of-tree kernel modules for SoCeteer IP) and
#     rootfs/ (dynamically linked userspace + root-filesystem staging) - see their READMEs
#   - the kernel Image, OpenSBI, and per-program boot images (<name>.BOOT.ELF: a kernel
#     with that program as the initramfs /init, wrapped in OpenSBI fw_payload)
#
# Requires the drop-in source trees binaries/linux-stable/ and binaries/opensbi/ and a
# host satisfying shared/cmake/SoctBootHostTools.cmake (LLVM with RISC-V + ld.lld,
# GNU make >= 4.0, GNU sed on macOS) - all missing pieces fail the configure loudly,
# with the remedy in the message.
################################################################################################

if (NOT DEFINED SOCT_CMAKE_PATH)
    cmake_path(GET CMAKE_CURRENT_LIST_DIR PARENT_PATH _binaries_dir)
    cmake_path(GET _binaries_dir PARENT_PATH _soceteer_root)
    file(REAL_PATH "${_soceteer_root}/shared/cmake" SOCT_CMAKE_PATH)
endif ()
list(PREPEND CMAKE_MODULE_PATH ${SOCT_CMAKE_PATH})

find_package(SOCTSystem REQUIRED) # Provides SOCT_SYSTEM pointing to the system file
message(STATUS "SOCT_SYSTEM: ${SOCT_SYSTEM}")
include(${SOCT_SYSTEM})

if (NOT DEFINED CMAKE_TOOLCHAIN_FILE)
    include(${SOCT_CMAKE_PATH}/toolchain-riscv-linux-musl.cmake)
endif ()

project(RV_Linux C)

include(ProcessorCount)
include(SoctBootHostTools)

set(SOCT_BOOT_LINUX_DIR "${CMAKE_CURRENT_SOURCE_DIR}/linux-stable" CACHE PATH
        "Linux kernel source tree")
set(SOCT_BOOT_OPENSBI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/opensbi" CACHE PATH
        "OpenSBI source tree")
set(DTC "dtc" CACHE STRING "Path to the device tree compiler binary")

if (NOT EXISTS "${SOCT_BOOT_LINUX_DIR}/Makefile" OR NOT EXISTS "${SOCT_BOOT_OPENSBI_DIR}/Makefile")
    message(FATAL_ERROR "The linux project requires the kernel and OpenSBI source trees at ${SOCT_BOOT_LINUX_DIR} and ${SOCT_BOOT_OPENSBI_DIR} (drop-in checkouts; see the binaries guide).")
endif ()

################################################################################################
# Kernel patches (patches/*.patch): minimal upstream-candidate fixes the boot depends on -
# see patches/README.md for the policy and each patch header for its rationale. Applied to
# the kernel tree once, idempotently: an already-applied patch is detected and skipped, a
# conflicting tree fails the configure loudly. `git apply` on purpose: it never prompts -
# macOS's BSD `patch` 2.0 answers its own "previously applied?" prompt with yes when
# stdin is silent, reporting unapplied patches as applied.
################################################################################################
file(GLOB _kernel_patches "${CMAKE_CURRENT_SOURCE_DIR}/patches/*.patch")
list(SORT _kernel_patches)
if (_kernel_patches)
    execute_process(COMMAND git -C "${SOCT_BOOT_LINUX_DIR}" rev-parse --is-inside-work-tree
            RESULT_VARIABLE _ktree_git OUTPUT_QUIET ERROR_QUIET)
    if (NOT _ktree_git EQUAL 0)
        message(FATAL_ERROR "linux: ${SOCT_BOOT_LINUX_DIR} is not a git tree, so the kernel patches in patches/ cannot be applied reliably - use a git checkout (the standard submodule), or apply them manually and make the tree a git repository.")
    endif ()
endif ()
foreach (_kpatch ${_kernel_patches})
    execute_process(COMMAND git -C "${SOCT_BOOT_LINUX_DIR}" apply --reverse --check "${_kpatch}"
            RESULT_VARIABLE _kpatch_applied OUTPUT_QUIET ERROR_QUIET)
    if (_kpatch_applied EQUAL 0)
        continue () # already applied
    endif ()
    execute_process(COMMAND git -C "${SOCT_BOOT_LINUX_DIR}" apply --check "${_kpatch}"
            RESULT_VARIABLE _kpatch_ok ERROR_VARIABLE _kpatch_err)
    if (NOT _kpatch_ok EQUAL 0)
        message(FATAL_ERROR "linux: kernel patch ${_kpatch} is neither applied nor applicable to ${SOCT_BOOT_LINUX_DIR}:\n${_kpatch_err}\nThe tree likely diverged from the version the patch was written against - rebase the patch or restore the tree.")
    endif ()
    execute_process(COMMAND git -C "${SOCT_BOOT_LINUX_DIR}" apply "${_kpatch}"
            RESULT_VARIABLE _kpatch_res)
    if (NOT _kpatch_res EQUAL 0)
        message(FATAL_ERROR "linux: failed to apply kernel patch ${_kpatch} (the --check passed - tree changed mid-configure?)")
    endif ()
    message(STATUS "linux: applied kernel patch ${_kpatch}")
endforeach ()
if (NOT DEFINED SOCT_MEM_BASE_ADDR OR NOT DEFINED SOCT_ELFS_DIR OR NOT DEFINED SOCT_DTS)
    message(FATAL_ERROR "The system file must define SOCT_MEM_BASE_ADDR, SOCT_ELFS_DIR and SOCT_DTS")
endif ()

soct_boot_find_host_tools()
if (NOT _path_ok)
    message(FATAL_ERROR "The build directory path contains ',' or ' ', which the kernel/OpenSBI makefiles cannot handle - use one without those characters (e.g. rename the IDE build profile).")
endif ()
if (NOT _llvm_ok)
    message(FATAL_ERROR "No RISC-V-capable LLVM (clang + ld.lld) found - install one (macOS: brew install llvm lld; Debian: clang + lld) or set -DSOCT_BOOT_LLVM_DIR.")
endif ()
if (NOT _make_ok)
    message(FATAL_ERROR "The kernel requires GNU make >= 4.0 and none was found - install one (macOS: brew install make) or set -DSOCT_BOOT_MAKE.")
endif ()
if (NOT _darwin_sed_ok)
    message(FATAL_ERROR "The kernel's generator scripts need GNU sed on macOS (BSD sed mangles the vDSO offsets header) - brew install gnu-sed.")
endif ()

ProcessorCount(_ncpu)
if (_ncpu EQUAL 0)
    set(_ncpu 4)
endif ()

################################################################################################
# The sysroot: kernel UAPI headers + static musl at the toolchain's CMAKE_SYSROOT
# (shared/vendor/riscv64-linux-musl-sysroot). Built at configure time when absent, so the
# IDE indexes against real headers from the first configure; the linux-sysroot target
# rebuilds it on demand.
################################################################################################
set(_musl_version "1.2.5")
set(_musl_sha256 "a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4")
set(_musl_tarball "${SOCETEER_ROOT}/shared/vendor/musl/musl-${_musl_version}.tar.gz")
if (NOT EXISTS "${_musl_tarball}")
    message(STATUS "linux: fetching musl ${_musl_version} (musl.libc.org, ~1 MB)")
    file(DOWNLOAD "https://musl.libc.org/releases/musl-${_musl_version}.tar.gz" "${_musl_tarball}"
            EXPECTED_HASH SHA256=${_musl_sha256} STATUS _musl_dl)
    list(GET _musl_dl 0 _musl_dl_code)
    if (NOT _musl_dl_code EQUAL 0)
        file(REMOVE "${_musl_tarball}")
        message(FATAL_ERROR "linux: musl download failed (${_musl_dl})")
    endif ()
endif ()
set(_musl_src "${CMAKE_CURRENT_BINARY_DIR}/musl-${_musl_version}")
if (NOT EXISTS "${_musl_src}/configure")
    file(ARCHIVE_EXTRACT INPUT "${_musl_tarball}" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}")
endif ()

# The rv64/lp64d libgcc of the bare-metal toolchain: its OS-agnostic builtins (soft
# fp128 etc.) stand in for the compiler-rt that prebuilt LLVM does not ship for cross
# targets. Must resolve to a real lp64d multilib, not the toolchain's default.
set(_xpack_gcc "${SOCETEER_ROOT}/shared/vendor/riscv-none-elf-gcc/bin/riscv-none-elf-gcc")
execute_process(COMMAND ${_xpack_gcc} -march=${SOCT_LINUX_MARCH} -mabi=lp64d -print-libgcc-file-name
        OUTPUT_VARIABLE _libgcc OUTPUT_STRIP_TRAILING_WHITESPACE)
if (NOT EXISTS "${_libgcc}" OR NOT _libgcc MATCHES "lp64d")
    message(FATAL_ERROR "linux: the vendored toolchain has no rv64/lp64d libgcc multilib (got '${_libgcc}')")
endif ()

set(_sysroot "${CMAKE_SYSROOT}")
set(_sysroot_stamp "${_sysroot}/.stamp")
if (CMAKE_HOST_APPLE AND _kenv)
    set(_hostbin_line "PATH=\"${CMAKE_CURRENT_BINARY_DIR}/hostbin:$ENV{PATH}\"; export PATH")
else ()
    set(_hostbin_line "")
endif ()
set(HOSTBIN_LINE "${_hostbin_line}")
set(BOOT_MAKE "${_boot_make}")
set(LINUX_DIR "${SOCT_BOOT_LINUX_DIR}")
set(HDR_BUILD "${CMAKE_CURRENT_BINARY_DIR}/sysroot-build/linux-headers")
set(SYSROOT "${_sysroot}")
set(MUSL_BUILD "${CMAKE_CURRENT_BINARY_DIR}/sysroot-build/musl")
set(MUSL_SRC "${_musl_src}")
set(CLANG "${_llvm_bindir}/clang")
set(LLD "${_llvm_lld}")
set(LLVM_AR "${_llvm_bindir}/llvm-ar")
set(LLVM_RANLIB "${_llvm_bindir}/llvm-ranlib")
set(MARCH "${SOCT_LINUX_MARCH}")
set(LIBGCC "${_libgcc}")
set(NCPU "${_ncpu}")
set(STAMP "${_sysroot_stamp}")
configure_file(build-sysroot.sh.in "${CMAKE_CURRENT_BINARY_DIR}/build-sysroot.sh" @ONLY)

if (NOT EXISTS "${_sysroot_stamp}")
    message(STATUS "linux: sysroot missing - building it now (kernel headers + musl, ~2 min)")
    execute_process(COMMAND sh "${CMAKE_CURRENT_BINARY_DIR}/build-sysroot.sh"
            RESULT_VARIABLE _sysroot_res)
    if (NOT _sysroot_res EQUAL 0)
        message(FATAL_ERROR "linux: sysroot build failed (see output above)")
    endif ()
endif ()
add_custom_command(OUTPUT "${_sysroot_stamp}"
        COMMAND sh "${CMAKE_CURRENT_BINARY_DIR}/build-sysroot.sh"
        DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/build-sysroot.sh" "${_musl_src}/configure"
        COMMENT "Building riscv64-linux-musl sysroot (kernel headers + musl)"
        VERBATIM)
add_custom_target(linux-sysroot DEPENDS "${_sysroot_stamp}")

################################################################################################
# BusyBox: a prebuilt static riscv64 userland for the shell boot image. Building BusyBox
# from source would mean a second kbuild frontier for one binary; Debian's busybox-static
# package is a hash-pinned, bit-exact stand-in (static glibc, rv64gc - runs on any
# SoCeteer core). Fetched once into shared/vendor/ from snapshot.debian.org, whose URLs
# are permanent - pool URLs disappear when a package version is superseded.
################################################################################################
set(_busybox_deb_name "busybox-static_1.37.0-6+b9_riscv64.deb")
set(_busybox_sha256 "4add476d2b185c5b487c285b38d790f0881a7e4a8e2ddde835f917e770eb8633")
set(_busybox_url "https://snapshot.debian.org/archive/debian/20260712T202631Z/pool/main/b/busybox/busybox-static_1.37.0-6%2Bb9_riscv64.deb")
set(SOCT_BUSYBOX "${SOCETEER_ROOT}/shared/vendor/busybox/busybox")
if (NOT EXISTS "${SOCT_BUSYBOX}")
    set(_busybox_deb "${SOCETEER_ROOT}/shared/vendor/busybox/${_busybox_deb_name}")
    if (NOT EXISTS "${_busybox_deb}")
        message(STATUS "linux: fetching ${_busybox_deb_name} (snapshot.debian.org, ~1 MB)")
        file(DOWNLOAD "${_busybox_url}" "${_busybox_deb}"
                EXPECTED_HASH SHA256=${_busybox_sha256} STATUS _bb_dl)
        list(GET _bb_dl 0 _bb_dl_code)
        if (NOT _bb_dl_code EQUAL 0)
            file(REMOVE "${_busybox_deb}")
            message(FATAL_ERROR "linux: busybox download failed (${_bb_dl})")
        endif ()
    endif ()
    # A .deb is an ar archive carrying the payload as data.tar.*; libarchive (behind
    # ARCHIVE_EXTRACT) reads both layers.
    set(_bb_x "${CMAKE_CURRENT_BINARY_DIR}/busybox-extract")
    file(REMOVE_RECURSE "${_bb_x}")
    file(ARCHIVE_EXTRACT INPUT "${_busybox_deb}" DESTINATION "${_bb_x}")
    file(GLOB _bb_data "${_bb_x}/data.tar.*")
    list(LENGTH _bb_data _bb_data_n)
    if (NOT _bb_data_n EQUAL 1)
        message(FATAL_ERROR "linux: ${_busybox_deb_name} is not a .deb (no data.tar.* inside)")
    endif ()
    file(ARCHIVE_EXTRACT INPUT "${_bb_data}" DESTINATION "${_bb_x}")
    if (NOT EXISTS "${_bb_x}/usr/bin/busybox")
        message(FATAL_ERROR "linux: ${_busybox_deb_name} does not contain usr/bin/busybox")
    endif ()
    file(COPY "${_bb_x}/usr/bin/busybox" "${_bb_x}/usr/share/doc/busybox-static/copyright"
            DESTINATION "${SOCETEER_ROOT}/shared/vendor/busybox")
    file(REMOVE_RECURSE "${_bb_x}")
    message(STATUS "linux: busybox ready at ${SOCT_BUSYBOX}")
endif ()

################################################################################################
# Kernel + OpenSBI machinery (shared by every boot image). The initramfs description is a
# FIXED path inside the kernel build dir; each program's boot image copies its own
# description there before building, so switching programs re-runs only the initramfs
# packing and the vmlinux link - never a reconfigure.
################################################################################################
set(_kbuild "${CMAKE_CURRENT_BINARY_DIR}/kernel")
set(_kdesc "${_kbuild}/initramfs.desc")
set(KBUILD_DESC "${_kdesc}")
configure_file(linux.fragment.in "${CMAKE_CURRENT_BINARY_DIR}/linux.fragment" @ONLY)
file(MAKE_DIRECTORY "${_kbuild}")
if (NOT EXISTS "${_kdesc}")
    file(WRITE "${_kdesc}" "# placeholder - overwritten by every <name>-boot-elf build\n")
endif ()

set(_kllvm "LLVM=${_llvm_bindir}/" "CROSS_COMPILE=riscv64-linux-gnu-")
add_custom_command(OUTPUT "${_kbuild}/.config"
        COMMAND sh -c "test ! -f '${SOCT_BOOT_LINUX_DIR}/.config' || { echo 'linux: in-tree .config found; run: make -C ${SOCT_BOOT_LINUX_DIR} mrproper' >&2; exit 1; }"
        COMMAND ${_kenv} ${_boot_make} -C "${SOCT_BOOT_LINUX_DIR}" ARCH=riscv
        ${_kllvm} O=${_kbuild} ${_khostflags} defconfig
        COMMAND sh -c "cat '${CMAKE_CURRENT_BINARY_DIR}/linux.fragment' >> '${_kbuild}/.config'"
        COMMAND ${_kenv} ${_boot_make} -C "${SOCT_BOOT_LINUX_DIR}" ARCH=riscv
        ${_kllvm} O=${_kbuild} ${_khostflags} olddefconfig
        DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/linux.fragment"
        COMMENT "Configuring kernel (defconfig + linux.fragment)"
        VERBATIM)
# Custom-command outputs are only visible within their own directory; boot images are
# created in program subdirectories, so they reach the kernel config and the DTB through
# these targets instead of the files.
add_custom_target(kernel-config DEPENDS "${_kbuild}/.config")

set(_boot_dtb "${CMAKE_CURRENT_BINARY_DIR}/boot.dtb")
add_custom_command(OUTPUT "${_boot_dtb}"
        COMMAND ${DTC} -I dts -O dtb -p 4096 -o "${_boot_dtb}" "${SOCT_DTS}"
        DEPENDS "${SOCT_DTS}"
        COMMENT "Building padded boot DTB from ${SOCT_DTS}"
        VERBATIM)
add_custom_target(boot-dtb DEPENDS "${_boot_dtb}")

math(EXPR _fw_text_start "${SOCT_MEM_BASE_ADDR}" OUTPUT_FORMAT HEXADECIMAL)
math(EXPR _fw_fdt_addr "${SOCT_MEM_BASE_ADDR} + 128 * 1024 * 1024" OUTPUT_FORMAT HEXADECIMAL)
set(_sbibuild "${CMAKE_BINARY_DIR}/opensbi")

# The kernel/OpenSBI build dirs are shared state: every boot image funnels through the
# soct_kbuild pool so two images never build concurrently (honored by Ninja).
set_property(GLOBAL PROPERTY JOB_POOLS soct_kbuild=1)

# Create the boot-image target chain for a userspace program: <name>.BOOT.ELF in
# SOCT_ELFS_DIR, a kernel with the program as /init, wrapped in OpenSBI fw_payload.
# Called by initram.cmake from the program's directory scope. By default the initramfs is
# the standard description with the program's executable as /init; DESC substitutes an
# already-configured description of the caller's own (with DEPENDS naming the files and
# targets it packs) - the shell image's route.
function(soct_linux_add_boot_image name)
    cmake_parse_arguments(_bi "" "DESC" "DEPENDS" ${ARGN})
    if (_bi_DESC)
        set(_desc "${_bi_DESC}")
    else ()
        set(_desc "${CMAKE_CURRENT_BINARY_DIR}/${name}-initramfs.desc")
        # configure_file cannot expand $<TARGET_FILE:...>; the executable's path is
        # deterministic (current binary dir + name), so spell it out.
        set(INIT_ELF "${CMAKE_CURRENT_BINARY_DIR}/${name}")
        configure_file("${CMAKE_SOURCE_DIR}/initramfs.desc.in" "${_desc}" @ONLY)
        set(_bi_DEPENDS ${name})
    endif ()

    set(_out "${SOCT_ELFS_DIR}/${name}.BOOT.ELF")
    # An always-run custom TARGET, deliberately not a file-dated custom command: kernel
    # SOURCE changes (or the patches applied at configure) are invisible to the build
    # system's dependency graph, and a dated command would happily declare a stale image
    # current. kbuild is its own dependency tracker - re-entering it costs seconds when
    # there is nothing to do - and copy_if_different keeps the output content-stable.
    add_custom_target(${name}-boot-elf
            COMMAND ${CMAKE_COMMAND} -E copy_if_different "${_desc}" "${_kdesc}"
            COMMAND ${_kenv} ${_boot_make} -C "${SOCT_BOOT_LINUX_DIR}" ARCH=riscv
            ${_kllvm} O=${_kbuild} ${_khostflags} -j${_ncpu} Image
            COMMAND ${CMAKE_COMMAND} -E make_directory "${_sbibuild}"
            COMMAND ${CMAKE_COMMAND} -E rm -f
            "${_sbibuild}/platform/generic/firmware/fw_payload.o"
            "${_sbibuild}/platform/generic/firmware/fw_payload.elf"
            "${_sbibuild}/platform/generic/firmware/fw_payload.bin"
            COMMAND ${_boot_make} -C "${SOCT_BOOT_OPENSBI_DIR}" O=${_sbibuild}
            LLVM=${_llvm_bindir}/ "USE_LD_FLAG=--ld-path=${_llvm_lld}"
            PLATFORM=generic PLATFORM_RISCV_XLEN=64 READLINK=readlink
            FW_PIC=n FW_TEXT_START=${_fw_text_start} FW_JUMP=n FW_DYNAMIC=n
            FW_PAYLOAD=y FW_PAYLOAD_OFFSET=0x200000
            FW_PAYLOAD_PATH=${_kbuild}/arch/riscv/boot/Image
            FW_FDT_PATH=${_boot_dtb} FW_PAYLOAD_FDT_ADDR=${_fw_fdt_addr} -j${_ncpu}
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${_sbibuild}/platform/generic/firmware/fw_payload.elf" "${_out}"
            COMMAND ${CMAKE_COMMAND} -E echo "${name}-boot-elf: ${_out} - copy to the SD card FAT root (as BOOT.ELF)"
            DEPENDS ${_bi_DEPENDS} "${_desc}"
            JOB_POOL soct_kbuild
            COMMENT "Building boot image ${name}.BOOT.ELF (kernel + ${name} as /init + OpenSBI)"
            VERBATIM)
    add_dependencies(${name}-boot-elf kernel-config boot-dtb)
endfunction()

################################################################################################
# Programs (auto-discovered; each includes userspace.cmake or initram.cmake)
################################################################################################
file(GLOB _linux_programs RELATIVE "${CMAKE_CURRENT_LIST_DIR}" "${CMAKE_CURRENT_LIST_DIR}/userspace/*/")
foreach (_entry ${_linux_programs})
    if (IS_DIRECTORY "${CMAKE_CURRENT_LIST_DIR}/${_entry}")
        add_subdirectory("${_entry}")
    endif ()
endforeach ()

# Canonical BOOT.ELF: one program's boot image under the name the sd-boot bootrom loads.
set(SOCT_BOOT_PROGRAM "shell" CACHE STRING
        "Userspace program whose boot image becomes the canonical BOOT.ELF")
if (NOT TARGET ${SOCT_BOOT_PROGRAM}-boot-elf)
    message(FATAL_ERROR "linux: SOCT_BOOT_PROGRAM='${SOCT_BOOT_PROGRAM}' has no boot image - choose a userspace/ program that includes initram.cmake (or calls soct_linux_add_boot_image itself).")
endif ()
# Always-run for the same reason as the boot images themselves; copy_if_different
# keeps BOOT.ELF untouched when the image did not change.
add_custom_target(boot-elf
        COMMAND ${CMAKE_COMMAND} -E copy_if_different "${SOCT_ELFS_DIR}/${SOCT_BOOT_PROGRAM}.BOOT.ELF" "${SOCT_ELFS_DIR}/BOOT.ELF"
        COMMENT "BOOT.ELF = ${SOCT_BOOT_PROGRAM}.BOOT.ELF"
        VERBATIM)
add_dependencies(boot-elf ${SOCT_BOOT_PROGRAM}-boot-elf)
