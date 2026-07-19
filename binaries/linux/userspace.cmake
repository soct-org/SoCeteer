# Template for Linux userspace programs - include after listing sources, mirroring the
# bare-metal soctglue-static.cmake:
#
#   list(APPEND CMAKE_C_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.c)
#   include(${CMAKE_CURRENT_LIST_DIR}/../../userspace.cmake)
#
# The target (named after the directory) is a real add_executable - IDEs index it - and
# produces a static riscv64-linux-musl ELF, copied to SOCT_ELFS_DIR. All cross flags
# (target triple, sysroot, static linking, libgcc runtime) come from the project's
# toolchain file; per-program options are ordinary target_compile_options after the
# include. For a program that should also become a bootable image (the program as the
# initramfs /init), include initram.cmake instead - it pulls this file in first.

get_filename_component(SOCT_PROGRAM ${CMAKE_CURRENT_SOURCE_DIR} NAME)

add_executable(${SOCT_PROGRAM} ${CMAKE_C_SRCS})
add_dependencies(${SOCT_PROGRAM} linux-sysroot)
message(STATUS "Adding Linux userspace program ${SOCT_PROGRAM}")

add_custom_command(TARGET ${SOCT_PROGRAM} POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:${SOCT_PROGRAM}>" "${SOCT_ELFS_DIR}/${SOCT_PROGRAM}"
        COMMENT "Installing ${SOCT_PROGRAM} to ${SOCT_ELFS_DIR}"
        VERBATIM)
