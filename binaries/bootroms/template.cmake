cmake_minimum_required(VERSION 3.20)

# This file can be included from each program's CMakeLists.txt file to set up. Feel free to just copy-paste and modify
# Input sources: CMAKE_CXX_SRCS, CMAKE_C_SRCS, CMAKE_ASM_SRCS
# Input flags: LFLAGS, CFLAGS - additional flags to pass to the linker and compiler
# ELF_DIR - directory to place output ELF files
# Targets: device_tree - target that builds the device tree blob
# Other input flags: MARCH, MABI, DTB_PATH, IMG_PATH, BOOTROM_ELF_PATH

get_filename_component(BOOTROM_NAME ${CMAKE_CURRENT_SOURCE_DIR} NAME)

message(STATUS "Adding bootrom: ${BOOTROM_NAME}")

set(ALL_CFLAGS -march=${MARCH} -mabi=${MABI} -mcmodel=medany -nostartfiles -Os -fno-pic -fno-common -g -Wall -Wextra)
set(ALL_LFLAGS -march=${MARCH} -mabi=${MABI} -static -nostartfiles -Wall -Wextra)

list(APPEND ALL_CFLAGS ${CFLAGS})
list(APPEND ALL_LFLAGS ${LFLAGS})

# Build the bootrom ELF
add_executable(${BOOTROM_NAME} ${CMAKE_CXX_SRCS} ${CMAKE_C_SRCS} ${CMAKE_ASM_SRCS})

add_dependencies(${BOOTROM_NAME} device_tree)

# Define DEVICE_TREE to point to the compiled DTB
target_compile_definitions(${BOOTROM_NAME} PRIVATE DEVICE_TREE=\"${DTB_PATH}\")
target_include_directories(${BOOTROM_NAME} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(${BOOTROM_NAME} PRIVATE ${ALL_CFLAGS})
target_link_options(${BOOTROM_NAME} PRIVATE ${ALL_LFLAGS})

set_target_properties(${BOOTROM_NAME} PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY ${ELF_DIR}/bootroms/${BOOTROM_NAME}
        OUTPUT_NAME bootrom.elf
)

add_custom_command(TARGET ${BOOTROM_NAME} POST_BUILD
        COMMAND ${CMAKE_OBJCOPY} -O binary --change-addresses=-0x10000 $<TARGET_FILE:${BOOTROM_NAME}> ${IMG_PATH}
        COMMENT "Generating bootrom image at ${IMG_PATH}"
        VERBATIM
)