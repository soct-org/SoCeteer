# Template for Linux userspace programs - the counterpart of soctglue-static.cmake for
# binaries that run under the Linux kernel (initramfs tools) instead of bare metal.
# Usage mirrors soctglue-static.cmake:
#
#   list(APPEND CMAKE_C_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.c)
#   include(${CMAKE_CURRENT_LIST_DIR}/../linux-userspace.cmake)
#
# The target is named after the directory and produces a STATIC riscv64-linux-musl
# ELF in SOCT_ELFS_DIR, compiled with the discovered LLVM against the sysroot from
# binaries/linux-sysroot (the kernel tree's UAPI headers + musl). Static because the
# target system is initramfs-only - there is no shared-library ecosystem to link
# against. Skipped with a status message when the sysroot is unavailable.
#
# Extra compile options: append to SOCT_LINUX_USERSPACE_FLAGS before including.

get_filename_component(SOCT_PROGRAM "${CMAKE_CURRENT_SOURCE_DIR}" NAME)

if (NOT SOCT_LINUX_USERSPACE_OK)
    message(STATUS "linux-userspace: sysroot unavailable - skipping ${SOCT_PROGRAM} (see the linux-sysroot messages)")
    return ()
endif ()

set(_lu_out "${SOCT_ELFS_DIR}/${SOCT_PROGRAM}")
add_custom_command(OUTPUT "${_lu_out}"
        COMMAND "${SOCT_LINUX_CLANG}" --target=riscv64-unknown-linux-musl
        -march=${SOCT_LINUX_MARCH} -mabi=lp64d "--sysroot=${SOCT_LINUX_SYSROOT}"
        "--ld-path=${SOCT_LINUX_LLD}" --rtlib=libgcc -static
        -O2 -Wall -Wextra ${SOCT_LINUX_USERSPACE_FLAGS}
        ${CMAKE_C_SRCS} -o "${_lu_out}"
        DEPENDS ${CMAKE_C_SRCS} "${SOCT_LINUX_SYSROOT_STAMP}"
        COMMENT "Building Linux userspace program ${SOCT_PROGRAM}"
        VERBATIM COMMAND_EXPAND_LISTS)
add_custom_target(${SOCT_PROGRAM} ALL DEPENDS "${_lu_out}")
add_dependencies(${SOCT_PROGRAM} linux-sysroot)
