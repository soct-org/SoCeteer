cmake_minimum_required(VERSION 3.20)

# This file can be included from each program's CMakeLists.txt file to set up. Feel free to just copy-paste and modify
# Input sources: CMAKE_CXX_SRCS, CMAKE_C_SRCS, CMAKE_ASM_SRCS
# Input flags: LFLAGS, CFLAGS - additional flags to pass to the linker and compiler
# ELF_DIR - directory to place output ELF files
# Targets: device_tree - target that builds the device tree blob
# Other input flags: SOCT_ARCH, SOCT_ABI, SOCT_XLEN, SOCT_BOOTROM_IMG

get_filename_component(BOOTROM_NAME ${CMAKE_CURRENT_SOURCE_DIR} NAME)

message(STATUS "Adding bootrom: ${BOOTROM_NAME}")

set(BOOTROM_ELF ${BOOTROM_NAME}_elf)
set(BOOTROM_IMG ${BOOTROM_NAME}_img)

set(ALL_CFLAGS -march=${SOCT_ARCH} -mabi=${SOCT_ABI} -mcmodel=medany -nostartfiles -Os -fno-pic -fno-common -g -Wall -Wextra)
set(ALL_LFLAGS -march=${SOCT_ARCH} -mabi=${SOCT_ABI} -static -nostartfiles -Wall -Wextra)

list(APPEND ALL_CFLAGS ${CFLAGS})
list(APPEND ALL_LFLAGS ${LFLAGS})

# Build the bootrom ELF
add_executable(${BOOTROM_ELF} ${CMAKE_CXX_SRCS} ${CMAKE_C_SRCS} ${CMAKE_ASM_SRCS})

add_dependencies(${BOOTROM_ELF} device_tree)

# Define DEVICE_TREE to point to the compiled DTB
target_compile_definitions(${BOOTROM_ELF} PRIVATE DEVICE_TREE=\"${SOCT_DTB}\")
target_include_directories(${BOOTROM_ELF} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(${BOOTROM_ELF} PRIVATE ${ALL_CFLAGS})
target_link_options(${BOOTROM_ELF} PRIVATE ${ALL_LFLAGS})

set_target_properties(${BOOTROM_ELF} PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY ${ELF_DIR}/bootroms/${BOOTROM_NAME}
        OUTPUT_NAME bootrom.elf
)

# Build the bootrom image
add_custom_target(${BOOTROM_IMG} ALL
        COMMAND ${CMAKE_COMMAND} -E remove -f ${SOCT_BOOTROM_IMG}
        COMMAND ${CMAKE_OBJCOPY} -O binary --change-addresses=-0x10000 $<TARGET_FILE:${BOOTROM_ELF}> ${SOCT_BOOTROM_IMG}
        DEPENDS ${BOOTROM_ELF}
        COMMENT "Always generating bootrom image at ${SOCT_BOOTROM_IMG}"
        VERBATIM
)

# Final target to build both ELF and IMG
add_custom_target(${BOOTROM_NAME} ALL
        DEPENDS ${BOOTROM_ELF} ${BOOTROM_IMG}
)