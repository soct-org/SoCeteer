cmake_minimum_required(VERSION 3.20)

set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)

# Parse the git root:
if (NOT DEFINED SOCETEER_ROOT)
    execute_process(
            COMMAND git rev-parse --show-toplevel
            WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
            OUTPUT_VARIABLE SOCETEER_ROOT
            OUTPUT_STRIP_TRAILING_WHITESPACE
    )
endif ()

message(STATUS "SOCETEER_ROOT: ${SOCETEER_ROOT}")

# fix the path to the shipped toolchain
include(${SOCETEER_ROOT}/buildtools/cmake/riscv-toolchain-shipped.cmake)

project(RiscVPrograms C CXX)

if (NOT DEFINED MARCH64)
    set(MARCH64 rv64imafdc_zifencei)
    message(WARNING "Using default MARCH64: ${MARCH64}. This may be COMPLETELY WRONG for your target system. Set via -DMARCH64=...")
endif()

if (NOT DEFINED MABI64)
    message(WARNING "Using default MABI64: ${MABI64}. This may be COMPLETELY WRONG for your target system. Set via -DMABI64=...")
    set(MABI64 lp64d)
endif()

if (NOT DEFINED MARCH32)
    message(WARNING "Using default MARCH32: ${MARCH32}. This may be COMPLETELY WRONG for your target system. Set via -DMARCH32=...")
    set(MARCH32 rv32imac_zifencei_zicsr)
endif()

if (NOT DEFINED MABI32)
    message(WARNING "Using default MABI32: ${MABI32}. This may be COMPLETELY WRONG for your target system. Set via -DMABI32=...")
    set(MABI32 ilp32)
endif()

# Add libgloss
set(LIBGLOSS_DIR ${SOCETEER_ROOT}/buildtools/libgloss)
add_subdirectory(${LIBGLOSS_DIR} ${CMAKE_BINARY_DIR}/libgloss)
include(${LIBGLOSS_DIR}/cmake/libgloss.cmake)

# Set the elf directory to the default if not defined
if (NOT DEFINED ELF_DIR)
    set(ELF_DIR ${SOCETEER_ROOT}/examples/elfs/)
    message(STATUS "Using default ELF_DIR: ${ELF_DIR}.")
endif ()

set(CMAKE_VERBOSE_MAKEFILE ON)

# iterate over all the programs in the programs directory
file(GLOB ALL_ENTRIES RELATIVE "${CMAKE_CURRENT_SOURCE_DIR}/programs" "${CMAKE_CURRENT_SOURCE_DIR}/programs/*")

set(PROGRAMS "")
foreach(ENTRY ${ALL_ENTRIES})
    if(IS_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/programs/${ENTRY}")
        list(APPEND PROGRAMS ${ENTRY})
    endif()
endforeach()

# Add each program as a subdirectory
foreach(PROGRAM ${PROGRAMS})
    add_subdirectory(programs/${PROGRAM} EXCLUDE_FROM_ALL)
endforeach()