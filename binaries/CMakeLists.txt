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
###########################################################################################
set(DTC "dtc" CACHE STRING "Path to the device tree compiler binary (dtc). If not set, looks for dtc in PATH.")

# Device tree building:
add_custom_command(
        OUTPUT "${SOCT_DTB}"
        COMMAND ${DTC} -I dts -O dtb -@ -A -o "${SOCT_DTB}" "${SOCT_DTS}"
        DEPENDS "${SOCT_DTS}"
        COMMENT "Building DTB from ${SOCT_DTS}"
)
add_custom_target(device_tree ALL DEPENDS "${SOCT_DTB}")

file(GLOB ALL_ENTRIES RELATIVE "${CMAKE_CURRENT_LIST_DIR}"
        "${CMAKE_CURRENT_LIST_DIR}/bootroms/*/"
        "${CMAKE_CURRENT_LIST_DIR}/programs/*/"
)

set(SOCTGLUE_DIR ${SOCETEER_ROOT}/shared/soctglue)
add_subdirectory(${SOCTGLUE_DIR} ${CMAKE_BINARY_DIR}/soctglue)
add_compile_definitions(${SOCT_COMPILE_DEFS})


# Add each program and bootrom as a subdirectory
foreach (ENTRY ${ALL_ENTRIES})
    set(ENTRY_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/${ENTRY}")
    if (IS_DIRECTORY "${ENTRY_SOURCE_DIR}")
        add_subdirectory("${ENTRY_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/${ENTRY}")
    endif ()
endforeach ()


###########################################################################################
# General targets and utilities
###########################################################################################
if (SOCT_FLASH_HOST OR SOCT_FLASH_XSDB)
    set(_rst_tgt "reset-core")
    set(_reset_wrapper "${CMAKE_BINARY_DIR}/${_rst_tgt}.sh")
    set(_reset_tcl "connect; targets -set -filter {name =~ {Hart #0*}}; rst -system; disconnect")

    if (SOCT_FLASH_HOST)
        set(_reset_content "\
#!/bin/sh
set -e
echo '[${_rst_tgt}] triggering on ${SOCT_FLASH_HOST} via xsdb...'
printf 'connect\\ntargets -set -filter {name =~ {Hart #0*}}\\nrst -system\\ndisconnect\\n' | ssh \"${SOCT_FLASH_HOST}\" \"${SOCT_FLASH_XSDB}\"
")
    else ()
        set(_reset_content "\
#!/bin/sh
set -e
echo '[${_rst_tgt}] triggering via xsdb...'
\"${SOCT_FLASH_XSDB}\" -eval \"${_reset_tcl}\"
")
    endif ()

    file(GENERATE OUTPUT "${_reset_wrapper}" CONTENT "${_reset_content}"
            FILE_PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE
            GROUP_READ GROUP_EXECUTE
            WORLD_READ WORLD_EXECUTE)

    add_custom_target(${_rst_tgt}
            COMMAND "${_reset_wrapper}"
            USES_TERMINAL
            VERBATIM
            COMMENT "Triggering ${_rst_tgt} via xsdb")
endif ()
