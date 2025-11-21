cmake_minimum_required(VERSION 3.20)

set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)

# Parse the git root:
if (NOT DEFINED SOCETEER_ROOT)
    execute_process(
            COMMAND git rev-parse --show-toplevel
            WORKING_DIRECTORY ${CMAKE_CURRENT_LIST_DIR}
            OUTPUT_VARIABLE SOCETEER_ROOT
            OUTPUT_STRIP_TRAILING_WHITESPACE
    )
endif ()

message(STATUS "SOCETEER_ROOT: ${SOCETEER_ROOT}")


macro(require_vars)
    foreach (var ${ARGN})
        if (NOT DEFINED ${var} OR "${${var}}" STREQUAL "")
            message(SEND_ERROR "Required CMake variable ${var} is not set. Use -D${var}=... to provide it.")
        endif ()
    endforeach ()
endmacro()

set(DEFAULT_TOOLCHAIN_FILE "${SOCETEER_ROOT}/shared/cmake/riscv-toolchain-shipped.cmake")
if (NOT DEFINED CMAKE_TOOLCHAIN_FILE)
    message(STATUS "Using default toolchain file: ${DEFAULT_TOOLCHAIN_FILE}. To override, set CMAKE_TOOLCHAIN_FILE.")
    include(${DEFAULT_TOOLCHAIN_FILE})
endif ()

project(RV_Binaries C CXX ASM)

set(ELF_DIR "${SOCETEER_ROOT}/binaries/elfs" CACHE STRING "Directory where to place the compiled programs/bootroms.")

###########################################################################################
# This Cmake can build both programs and bootroms.
# Set BOOTROM_MODE to build bootroms, otherwise programs to execute on the board/simulator.
###########################################################################################
if (DEFINED BOOTROM_MODE)
    message(NOTICE "Configured to build RISC-V bootroms.")
    # Required variables for bootroms:
    set(DTC "dtc" CACHE STRING "Path to the device tree compiler binary (dtc). If not set, looks for dtc in PATH.")

    # Validate required variables
    require_vars(MARCH MABI DTS_PATH IMG_PATH)

    # Device tree building:
    get_filename_component(DTS_NAME ${DTS_PATH} NAME_WE)
    set(DTB_PATH "${CMAKE_CURRENT_BINARY_DIR}/${DTS_NAME}.dtb")
    add_custom_command(
            OUTPUT "${DTB_PATH}"
            COMMAND ${DTC} -I dts -O dtb -@ -A -o "${DTB_PATH}" "${DTS_PATH}"
            DEPENDS "${DTS_PATH}"
            COMMENT "Building DTB from ${DTS_PATH}"
    )
    add_custom_target(device_tree ALL DEPENDS "${DTB_PATH}")

    # Optional variable for bootroms:
    set(DTC "dtc" CACHE STRING "Path to the device tree compiler binary (dtc). If not set, looks for dtc in PATH.")

    file(GLOB ALL_ENTRIES RELATIVE "${CMAKE_CURRENT_LIST_DIR}/bootroms" "${CMAKE_CURRENT_LIST_DIR}/bootroms/*/")
else () # PROGRAM MODE
    message(NOTICE "Configured to build RISC-V programs.")

    # For convenience, this project builds both 32-bit and 64-bit binaries by default.
    if (NOT DEFINED MARCH64)
        set(MARCH64 rv64imafdc_zifencei)
        message(NOTICE "Using default MARCH64: ${MARCH64}. This may be COMPLETELY WRONG for your target system. Set via -DMARCH64=...")
    endif ()

    if (NOT DEFINED MARCH32)
        set(MARCH32 rv32imac_zifencei_zicsr)
        message(NOTICE "Using default MARCH32: ${MARCH32}. This may be COMPLETELY WRONG for your target system. Set via -DMARCH32=...")
    endif ()

    if (NOT DEFINED MABI64)
        set(MABI64 lp64d)
        message(NOTICE "Using default MABI64: ${MABI64}. This may be COMPLETELY WRONG for your target system. Set via -DMABI64=...")
    endif ()

    if (NOT DEFINED MABI32)
        set(MABI32 ilp32)
        message(NOTICE "Using default MABI32: ${MABI32}. This may be COMPLETELY WRONG for your target system. Set via -DMABI32=...")
    endif ()

    # Add libgloss
    set(LIBGLOSS_DIR ${SOCETEER_ROOT}/shared/libgloss)
    add_subdirectory(${LIBGLOSS_DIR} ${CMAKE_BINARY_DIR}/libgloss)
    include(${LIBGLOSS_DIR}/cmake/libgloss.cmake) # Include libgloss CMake variables

    file(GLOB ALL_ENTRIES RELATIVE "${CMAKE_CURRENT_LIST_DIR}/programs" "${CMAKE_CURRENT_LIST_DIR}/programs/*/")
endif ()


# Add each program and bootrom as a subdirectory
foreach (ENTRY ${ALL_ENTRIES})
    if (DEFINED BOOTROM_MODE)
        set(BOOTROM ${ENTRY})
        set(_path ${CMAKE_CURRENT_LIST_DIR}/bootroms/${BOOTROM})
        if (EXISTS "${_path}/CMakeLists.txt")
            add_subdirectory(${_path} EXCLUDE_FROM_ALL)
        endif ()
    else ()
        set(PROGRAM ${ENTRY})
        set(_path ${CMAKE_CURRENT_LIST_DIR}/programs/${PROGRAM})
        if (EXISTS ${_path}/CMakeLists.txt)
            add_subdirectory(${_path} EXCLUDE_FROM_ALL)
        endif ()
    endif ()
endforeach ()