cmake_minimum_required(VERSION 3.20)
project(libgloss C ASM)

set(CMAKE_C_STANDARD 11)
set(CMAKE_ASM_COMPILE_OPTIONS_SYSROOT "") # Prevent sysroot from being passed to assembler

if(NOT DEFINED SOCT_XLEN OR NOT DEFINED SOCT_ARCH OR NOT DEFINED SOCT_ABI)
    message(FATAL_ERROR "SOCT_XLEN (the target XLEN), SOCT_ARCH (the target arch) and SOCT_ABI (the target abi) must be defined to build libgloss.")
endif()

set(LIBGLOSS_DIR ${CMAKE_CURRENT_LIST_DIR})
set(INCLUDE_DIRS ${LIBGLOSS_DIR}/include)
set(HTIF_DIR ${LIBGLOSS_DIR}/htif)
set(BOARD_DIR ${LIBGLOSS_DIR}/board)

# Common sources
file(GLOB_RECURSE LIBGLOSS_COMMON_C_SRCS ${LIBGLOSS_DIR}/src/**/*.c)
file(GLOB_RECURSE HTIF_C_SRCS ${HTIF_DIR}/**/*.c)
file(GLOB_RECURSE HTIF_ASM_SRCS ${HTIF_DIR}/**/*.S)
file(GLOB_RECURSE BOARD_C_SRCS ${BOARD_DIR}/**/*.c)
file(GLOB_RECURSE BOARD_ASM_SRCS ${BOARD_DIR}/**/*.S)

# Unified flag generators
function(set_libgloss_flags march mabi out_cflags out_asmflags)
    set(${out_cflags}
            -march=${march} -mabi=${mabi} -mcmodel=medany
            -I${INCLUDE_DIRS}
            -fno-builtin -nostartfiles -fno-common -fPIC -std=c11 -Wall -Wextra
            PARENT_SCOPE)
    set(${out_asmflags}
            -march=${march} -mabi=${mabi} -mcmodel=medany
            -I${INCLUDE_DIRS}
            -fno-builtin -nostartfiles -fno-common -fPIC -x assembler-with-cpp -D__ASSEMBLY__
            PARENT_SCOPE)
endfunction()

# Helper function for object libraries
function(add_obj_library name c_srcs asm_srcs extra_includes cflags asmflags)
    add_library(${name} OBJECT ${c_srcs} ${asm_srcs})
    target_include_directories(${name} PRIVATE ${extra_includes} ${INCLUDE_DIRS})
    target_compile_options(${name} PRIVATE
            $<$<COMPILE_LANGUAGE:C>:${cflags}>
            $<$<COMPILE_LANGUAGE:ASM>:${asmflags}>
    )
endfunction()

# Helper for static libraries
function(add_static_library name objlib1 objlib2 outname)
    add_library(${name} STATIC
            $<TARGET_OBJECTS:${objlib1}>
            $<TARGET_OBJECTS:${objlib2}>
    )
    set_target_properties(${name} PROPERTIES OUTPUT_NAME ${outname})
endfunction()

set_libgloss_flags(${SOCT_ARCH} ${SOCT_ABI} CFLAGS ASMFLAGS)

add_obj_library(common_objs "${LIBGLOSS_COMMON_C_SRCS}" "" "${INCLUDE_DIRS}" "${CFLAGS}" "${ASMFLAGS}")
add_obj_library(gloss_htif_objs "${HTIF_C_SRCS}" "${HTIF_ASM_SRCS}" "${HTIF_DIR}/misc;${INCLUDE_DIRS};${INCLUDE_DIRS}/htif" "${CFLAGS}" "${ASMFLAGS}")
add_obj_library(gloss_board_objs "${BOARD_C_SRCS}" "${BOARD_ASM_SRCS}" "${BOARD_DIR}/misc;${INCLUDE_DIRS};${INCLUDE_DIRS}/board" "${CFLAGS}" "${ASMFLAGS}")

add_static_library(libgloss_htif gloss_htif_objs common_objs gloss_htif)
add_static_library(libgloss_board gloss_board_objs common_objs gloss_board)