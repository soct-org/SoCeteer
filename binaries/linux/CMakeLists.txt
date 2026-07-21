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
# and a host satisfying shared/cmake/SoctBootHostTools.cmake (LLVM with RISC-V + ld.lld,
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
