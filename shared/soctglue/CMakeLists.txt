cmake_minimum_required(VERSION 3.20)

###############################################################################################
# This Project is a glue layer that provides common code for both the bootrom and the programs.
# It is meant to be built from the binaries directory where you can find sensible defaults
###############################################################################################
if(NOT DEFINED SOCETEER_VERSION)
    message(FATAL_ERROR "SOCETEER_VERSION is not defined. You need to add a SOCT_SYSTEM file to build this project")
endif()

project(soctglue C ASM)

set(CMAKE_C_STANDARD 11)
set(CMAKE_ASM_COMPILE_OPTIONS_SYSROOT "") # Prevent sysroot from being passed to assembler

set(SOCTGLUE_DIR ${CMAKE_CURRENT_LIST_DIR})
set(SOCTGLUE_SRC ${SOCTGLUE_DIR}/src)
set(INCLUDE_DIRS ${SOCTGLUE_DIR}/include)

file(GLOB SOCTGLUE_C_SRCS ${SOCTGLUE_SRC}/stdio/*.c  ${SOCTGLUE_SRC}/posix/*.c ${SOCTGLUE_SRC}/*.c)
file(GLOB_RECURSE SOCTGLUE_ASM_SRCS ${SOCTGLUE_SRC}/**/*.S ${SOCTGLUE_SRC}/*.S)

set(ASMFLAGS
        -march=${SOCT_ARCH}
        -mabi=${SOCT_ABI}
        -mcmodel=medany
        -fno-builtin
        -nostartfiles
        -fno-common
        -fPIC
        -x assembler-with-cpp
)

set(CFLAGS
        -march=${SOCT_ARCH}
        -mabi=${SOCT_ABI}
        -g3
        -g0
        -Os
        -mcmodel=medany
        -fno-builtin
        -nostartfiles
        -nodefaultlibs
        -fno-common
        -fPIC
        -Wall
        -Wextra
)

add_library(soctglue STATIC ${SOCTGLUE_C_SRCS} ${SOCTGLUE_ASM_SRCS})

if(SOCT_NEEDS_FATFS)
    message(STATUS "SOCT_NEEDS_FATFS is defined, including FatFS sources in soctglue")
    target_compile_definitions(soctglue PUBLIC SOCT_NEEDS_FATFS)
    file(GLOB_RECURSE SOCTGLUE_SOCT_FF_SRCS ${SOCTGLUE_SRC}/soct_ff/*.c)
    target_sources(soctglue PRIVATE ${SOCTGLUE_SOCT_FF_SRCS})
else ()
    message(STATUS "SOCT_NEEDS_FATFS is not defined, skipping FatFS sources in soctglue")
endif()

target_include_directories(soctglue PUBLIC ${INCLUDE_DIRS} ${SOCTGLUE_SRC})

target_compile_options(soctglue PRIVATE
        $<$<COMPILE_LANGUAGE:C>:${CFLAGS}>
        $<$<COMPILE_LANGUAGE:ASM>:${ASMFLAGS}>
)

