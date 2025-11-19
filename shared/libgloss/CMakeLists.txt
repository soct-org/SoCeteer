cmake_minimum_required(VERSION 3.20)
project(libgloss C ASM)

set(CMAKE_C_STANDARD 11)
set(CMAKE_ASM_COMPILE_OPTIONS_SYSROOT "") # Prevent sysroot from being passed to assembler

# Require MARCH and MABI to be set by parent project
if(NOT DEFINED MARCH64 OR NOT DEFINED MABI64 OR NOT DEFINED MARCH32 OR NOT DEFINED MABI32)
    message(FATAL_ERROR "MARCH64, MABI64, MARCH32, and MABI32 must be defined to build libgloss.")
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

set_libgloss_flags(${MARCH64} ${MABI64} CFLAGS64 ASMFLAGS64)
set_libgloss_flags(${MARCH32} ${MABI32} CFLAGS32 ASMFLAGS32)

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

# 64-bit object libraries
add_obj_library(common_objs_64 "${LIBGLOSS_COMMON_C_SRCS}" "" "${INCLUDE_DIRS}" "${CFLAGS64}" "${ASMFLAGS64}")
add_obj_library(gloss_htif_objs_64 "${HTIF_C_SRCS}" "${HTIF_ASM_SRCS}" "${HTIF_DIR}/misc;${INCLUDE_DIRS};${INCLUDE_DIRS}/htif" "${CFLAGS64}" "${ASMFLAGS64}")
add_obj_library(gloss_board_objs_64 "${BOARD_C_SRCS}" "${BOARD_ASM_SRCS}" "${BOARD_DIR}/misc;${INCLUDE_DIRS};${INCLUDE_DIRS}/board" "${CFLAGS64}" "${ASMFLAGS64}")

add_static_library(libgloss64_htif gloss_htif_objs_64 common_objs_64 gloss64_htif)
add_static_library(libgloss64_board gloss_board_objs_64 common_objs_64 gloss64_board)

# 32-bit object libraries
add_obj_library(common_objs_32 "${LIBGLOSS_COMMON_C_SRCS}" "" "${INCLUDE_DIRS}" "${CFLAGS32}" "${ASMFLAGS32}")
add_obj_library(gloss_htif_objs_32 "${HTIF_C_SRCS}" "${HTIF_ASM_SRCS}" "${HTIF_DIR}/misc;${INCLUDE_DIRS};${INCLUDE_DIRS}/htif" "${CFLAGS32}" "${ASMFLAGS32}")
add_obj_library(gloss_board_objs_32 "${BOARD_C_SRCS}" "${BOARD_ASM_SRCS}" "${BOARD_DIR}/misc;${INCLUDE_DIRS};${INCLUDE_DIRS}/board" "${CFLAGS32}" "${ASMFLAGS32}")

add_static_library(libgloss32_htif gloss_htif_objs_32 common_objs_32 gloss32_htif)
add_static_library(libgloss32_board gloss_board_objs_32 common_objs_32 gloss32_board)