###############################
# This script locates or installs the RISC-V toolchain.
# It has the following precedence for finding the toolchain:
# 1. User-defined RISCV_TOOLS variable (-DRISCV_TOOLS=...)
# 2. RISCV_TOOLS environment variable
# 3. Default path relative to the project - RISCV_TOOLS=../vendor/riscv-none-elf-gcc
#
# 1. and 2. expect to find the toolchain binaries in "$RISCV_TOOLS/bin/".
# If it does not exist, this script will jump try to install at 3.
###############################
cmake_minimum_required(VERSION 3.20)

# The RISC-V toolchain used by default (relative to this file)
cmake_path(SET RISCV_TOOLS_DEFAULT NORMALIZE "${CMAKE_CURRENT_LIST_DIR}/../vendor/riscv-none-elf-gcc")
if (NOT DEFINED RISCV_TOOLS_VERSION)
    set(RISCV_TOOLS_VERSION "15.2.0-1")
endif ()

# Include the installation function
include(${CMAKE_CURRENT_LIST_DIR}/install-riscv-tools.cmake)

# 1.
if (DEFINED RISCV_TOOLS AND EXISTS "${RISCV_TOOLS}/bin")
    message(STATUS "Using defined RISCV_TOOLS: ${RISCV_TOOLS}")
# 2.
elseif (DEFINED ENV{RISCV_TOOLS} AND EXISTS "$ENV{RISCV_TOOLS}/bin")
    set(RISCV_TOOLS $ENV{RISCV_TOOLS})
    message(STATUS "Using RISCV_TOOLS from environment: ${RISCV_TOOLS}")
# 3.
else ()
    set(RISCV_TOOLS ${RISCV_TOOLS_DEFAULT})
    if (NOT EXISTS "${RISCV_TOOLS}/bin")
        message(STATUS "RISC-V tools not found. Installing to default location: ${RISCV_TOOLS_DEFAULT}")
        install_riscv_tools()
        if (NOT EXISTS "${RISCV_TOOLS}/bin")
            message(FATAL_ERROR "RISC-V tools not found at ${RISCV_TOOLS}/bin after installation.")
        endif ()
    else ()
        message(STATUS "Using default RISC-V tools at: ${RISCV_TOOLS_DEFAULT}")
    endif ()
endif ()

# Where to find the compiler toolchain.
set(RV_PREFIX ${RISCV_TOOLS}/bin/riscv-none-elf-)

include(${CMAKE_CURRENT_LIST_DIR}/_riscv-toolchain.cmake)