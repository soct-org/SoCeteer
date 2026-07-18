cmake_minimum_required(VERSION 3.20)

# This file can be included from each program's CMakeLists.txt file to set up. Feel free to just copy-paste and modify
# Input sources: CMAKE_CXX_SRCS, CMAKE_C_SRCS, CMAKE_ASM_SRCS
# Targets: device_tree - target that builds the device tree blob
# Requires SOCT_SYSTEM

get_filename_component(SOCT_BOOTROM ${CMAKE_CURRENT_SOURCE_DIR} NAME)

message(STATUS "Adding bootrom: ${SOCT_BOOTROM}")

set(BOOTROM_ELF ${SOCT_BOOTROM}_elf)
set(BOOTROM_IMG ${SOCT_BOOTROM}_img)

# All bootroms share one linker script, which pins _start, the reset vector and the
# embedded DTB at the addresses the system file provides (SOCT_BOOTROM_BASE_ADDR /
# SOCT_BOOTROM_HANG_ADDR / SOCT_DTB_ADDR). Override by setting SOCT_LD_SCRIPT before
# including this template.
if (NOT DEFINED SOCT_LD_SCRIPT)
    set(SOCT_LD_SCRIPT ${CMAKE_CURRENT_LIST_DIR}/bootrom.lds)
endif ()

set(ALL_CFLAGS -march=${SOCT_ARCH} -mabi=${SOCT_ABI} -nostartfiles -Os -fno-pic -fno-common -g -Wall -Wextra)
set(ALL_LFLAGS -march=${SOCT_ARCH} -mabi=${SOCT_ABI} -static -nostartfiles -Wall -Wextra -T ${SOCT_LD_SCRIPT})

list(APPEND ALL_CFLAGS ${CFLAGS})
list(APPEND ALL_LFLAGS ${LFLAGS})

# Build the bootrom ELF
add_executable(${BOOTROM_ELF} ${CMAKE_CXX_SRCS} ${CMAKE_C_SRCS} ${CMAKE_ASM_SRCS})

add_dependencies(${BOOTROM_ELF} device_tree)

# Define SOCT_DTB to point to the compiled DTB
target_compile_definitions(${BOOTROM_ELF} PRIVATE DEVICE_TREE=\"${SOCT_DTB}\")
target_include_directories(${BOOTROM_ELF} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(${BOOTROM_ELF} PRIVATE ${ALL_CFLAGS})
target_link_options(${BOOTROM_ELF} PRIVATE ${ALL_LFLAGS})

set_target_properties(${BOOTROM_ELF} PROPERTIES OUTPUT_NAME ${SOCT_BOOTROM}.elf
        RUNTIME_OUTPUT_DIRECTORY ${SOCT_ELFS_DIR}
)

# Build the bootrom image
add_custom_target(${BOOTROM_IMG} ALL
        COMMAND ${CMAKE_COMMAND} -E remove -f ${SOCT_BOOTROM_IMG}
        # Rebase the image to offset 0 by subtracting the ROM base address
        COMMAND ${CMAKE_OBJCOPY} -O binary --change-addresses=-${SOCT_BOOTROM_BASE_ADDR} $<TARGET_FILE:${BOOTROM_ELF}> ${SOCT_BOOTROM_IMG}
        DEPENDS ${BOOTROM_ELF}
        COMMENT "Always generating bootrom image at ${SOCT_BOOTROM_IMG}"
        VERBATIM
)

# Final target to build both ELF and IMG
add_custom_target(${SOCT_BOOTROM} ALL DEPENDS ${BOOTROM_ELF} ${BOOTROM_IMG})