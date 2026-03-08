cmake_minimum_required(VERSION 3.20)

set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)

############################################################################
# SOCT_CMAKE_PATH is the path to the shared cmake files in the SoCeteer repo
############################################################################
if (NOT DEFINED SOCT_CMAKE_PATH)
    cmake_path(GET CMAKE_CURRENT_LIST_DIR PARENT_PATH _soceteer_root)
    file(REAL_PATH "${_soceteer_root}/shared/cmake" SOCT_CMAKE_PATH)
    message(STATUS "SOCT_CMAKE_PATH: ${SOCT_CMAKE_PATH}")
endif ()
list(PREPEND CMAKE_MODULE_PATH ${SOCT_CMAKE_PATH})

find_package(SOCTSystem REQUIRED) # Provides SOCT_SYSTEM variable pointing to the system file with important variables for the project
message(STATUS "SOCT_SYSTEM: ${SOCT_SYSTEM}")
include(${SOCT_SYSTEM}) # Include the system file to get important variables for the project


set(SOCT_TOOLCHAIN_FILE "${SOCETEER_ROOT}/shared/cmake/toolchain-riscv.cmake")
if (NOT DEFINED CMAKE_TOOLCHAIN_FILE)
    message(STATUS "Using default toolchain file: ${SOCT_TOOLCHAIN_FILE}. To override, set CMAKE_TOOLCHAIN_FILE.")
    include(${SOCT_TOOLCHAIN_FILE})
endif ()

project(RV_Binaries C CXX ASM)

###########################################################################################
# This Cmake can build both programs and bootroms.
# Set BOOTROM_MODE=ON to build bootroms, otherwise programs to execute on the board/simulator.
###########################################################################################
if (DEFINED BOOTROM_MODE)
    message(NOTICE "Configured to build RISC-V bootroms.")
    # Required variables for bootroms:
    set(DTC "dtc" CACHE STRING "Path to the device tree compiler binary (dtc). If not set, looks for dtc in PATH.")

    # Device tree building:
    add_custom_command(
            OUTPUT "${SOCT_DTB}"
            COMMAND ${DTC} -I dts -O dtb -@ -A -o "${SOCT_DTB}" "${SOCT_DTS}"
            DEPENDS "${SOCT_DTS}"
            COMMENT "Building DTB from ${SOCT_DTS}"
    )
    add_custom_target(device_tree ALL DEPENDS "${SOCT_DTB}")

    file(GLOB ALL_ENTRIES RELATIVE "${CMAKE_CURRENT_LIST_DIR}/bootroms" "${CMAKE_CURRENT_LIST_DIR}/bootroms/*/")
else () # PROGRAM MODE
    message(NOTICE "Configured to build RISC-V programs.")

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