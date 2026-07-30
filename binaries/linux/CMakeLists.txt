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
#   - out-of-tree kernel modules under drivers/ (auto-discovered; packed into boot
#     images' initramfs) and kernel patches under patches/ - see their READMEs
#   - reserved for the future: rootfs/ (dynamically linked userspace + root-filesystem
#     staging) - see its README
#   - the kernel Image, OpenSBI, and per-program boot images (<name>.BOOT.ELF: a kernel
#     with that program as the initramfs /init, wrapped in OpenSBI fw_payload)
#
# This file only bootstraps and orchestrates; the machinery lives in cmake/, one concern
# per file (each states what it consumes and defines):
#   cmake/kernel-patches.cmake  - applies patches/*.patch to the kernel tree, idempotently
#   cmake/sysroot.cmake         - musl fetch + riscv64-linux-musl sysroot, linux-sysroot target
#   cmake/busybox.cmake         - hash-pinned prebuilt BusyBox fetch (SOCT_BUSYBOX)
#   cmake/boot-image.cmake      - kernel-config/boot-dtb, soct_linux_add_boot_image()
#   cmake/kernel-modules.cmake  - <name>-driver module builds + <name>-index IDE targets
#
# Requires the drop-in source trees binaries/linux/linux-stable/ and binaries/linux/opensbi/
# (deliberately not submodules - the kernel tree would dominate every repository clone)
# and a host satisfying shared/cmake/SoctBootHostTools.cmake (LLVM with RISC-V + ld.lld,
# GNU make >= 4.0, GNU sed on macOS, dtc, kbuild's flex/bison/bc). The configure probes
# ALL of it and fails with one consolidated list of everything missing, each item with
# the install/clone command that provides it.
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

if (NOT DEFINED SOCT_MEM_BASE_ADDR OR NOT DEFINED SOCT_ELFS_DIR OR NOT DEFINED SOCT_DTS)
    message(FATAL_ERROR "The system file must define SOCT_MEM_BASE_ADDR, SOCT_ELFS_DIR and SOCT_DTS")
endif ()

################################################################################################
# Prerequisites - probed ALL at once: one configure run reports EVERYTHING that is missing,
# each item with the command that provides it, instead of dying at the first gap and making
# the user iterate. (A broken SOCT_SYSTEM file above is a different failure class - wrong
# input, not a missing host piece - and stays an immediate error.)
################################################################################################
set(_linux_missing "")

# Records one missing prerequisite: `what` names it, every further argument is a remedy
# line (a command to run, or an explanation), shown indented beneath it.
function(soct_linux_missing what)
    # NB: remedy lines must not contain ';' - ARGN expansion would split them.
    set(_entry "\n  * ${what}")
    foreach (_remedy ${ARGN})
        string(APPEND _entry "\n      ${_remedy}")
    endforeach ()
    set(_linux_missing "${_linux_missing}${_entry}" PARENT_SCOPE)
endfunction()

if (NOT EXISTS "${SOCT_BOOT_LINUX_DIR}/Makefile")
    # v7.2-rc3 is the version patches/ and drivers/ are developed against (also the pin in
    # the README quick start - SOCTReadmeBuilder.linuxKernelTag); newer kernels may work,
    # and an incompatible one fails loudly when the patches no longer apply.
    soct_linux_missing("The Linux kernel source tree (expected at ${SOCT_BOOT_LINUX_DIR})"
            "git clone --depth 1 --branch v7.2-rc3 https://github.com/gregkh/linux.git ${SOCT_BOOT_LINUX_DIR}"
            "(the tested version - a newer kernel may work, incompatible patches fail loudly)")
endif ()
if (NOT EXISTS "${SOCT_BOOT_OPENSBI_DIR}/Makefile")
    soct_linux_missing("The OpenSBI source tree (expected at ${SOCT_BOOT_OPENSBI_DIR})"
            "git clone --depth 1 https://github.com/riscv-software-src/opensbi.git ${SOCT_BOOT_OPENSBI_DIR}")
endif ()

soct_boot_find_host_tools()
if (NOT _path_ok)
    soct_linux_missing("A build directory path without ',' or ' ' - the kernel/OpenSBI makefiles cannot handle either (this one: '${CMAKE_CURRENT_BINARY_DIR}')"
            "re-run CMake with a different -B path (e.g. rename the IDE build profile)")
endif ()
if (NOT _llvm_ok)
    soct_linux_missing("A RISC-V-capable LLVM (clang + ld.lld)"
            "macOS:         brew install llvm lld"
            "Debian/Ubuntu: apt install clang lld"
            "or point at an install: -DSOCT_BOOT_LLVM_DIR=<llvm bin directory>")
endif ()
if (NOT _make_ok)
    soct_linux_missing("GNU make >= 4.0 (kernel requirement)"
            "macOS:         brew install make"
            "Debian/Ubuntu: apt install make"
            "or -DSOCT_BOOT_MAKE=<path>")
endif ()
if (NOT _darwin_sed_ok)
    soct_linux_missing("GNU sed (the kernel's generator scripts on macOS; BSD sed mangles the vDSO offsets header)"
            "brew install gnu-sed")
endif ()

# The device tree compiler is only invoked at build time (boot-image.cmake), so probe it
# here where the miss is reportable with a remedy instead of a mid-build command failure.
find_program(_soct_dtc_probe "${DTC}")
if (NOT _soct_dtc_probe)
    soct_linux_missing("The device tree compiler ('${DTC}' not found)"
            "macOS:         brew install dtc"
            "Debian/Ubuntu: apt install device-tree-compiler"
            "or -DDTC=<path>")
endif ()

# Host utilities kbuild expects beyond make (kconfig lexer/parser, timeconst).
set(_kernel_utils_missing "")
foreach (_tool flex bison bc)
    find_program(_soct_${_tool}_probe "${_tool}")
    if (NOT _soct_${_tool}_probe)
        list(APPEND _kernel_utils_missing "${_tool}")
    endif ()
endforeach ()
if (_kernel_utils_missing)
    list(JOIN _kernel_utils_missing " " _kernel_utils_missing)
    soct_linux_missing("Kernel host utilities: ${_kernel_utils_missing}"
            "macOS:         ship with the Xcode command line tools (xcode-select --install)"
            "Debian/Ubuntu: apt install ${_kernel_utils_missing}")
endif ()

if (_linux_missing)
    message(FATAL_ERROR "The linux project is missing prerequisites:\n${_linux_missing}\n\nProvide the above and re-run CMake. Background: the Booting Linux guide (docs/guides/linux.html).")
endif ()

ProcessorCount(_ncpu)
if (_ncpu EQUAL 0)
    set(_ncpu 4)
endif ()

include(${CMAKE_CURRENT_LIST_DIR}/cmake/kernel-patches.cmake)
include(${CMAKE_CURRENT_LIST_DIR}/cmake/sysroot.cmake)
include(${CMAKE_CURRENT_LIST_DIR}/cmake/busybox.cmake)
include(${CMAKE_CURRENT_LIST_DIR}/cmake/boot-image.cmake)
include(${CMAKE_CURRENT_LIST_DIR}/cmake/kernel-modules.cmake)

################################################################################################
# Programs (auto-discovered; each includes userspace.cmake or initram.cmake)
################################################################################################
file(GLOB _linux_programs RELATIVE "${CMAKE_CURRENT_LIST_DIR}" "${CMAKE_CURRENT_LIST_DIR}/userspace/*/")
foreach (_entry ${_linux_programs})
    if (IS_DIRECTORY "${CMAKE_CURRENT_LIST_DIR}/${_entry}")
        add_subdirectory("${_entry}")
    endif ()
endforeach ()
