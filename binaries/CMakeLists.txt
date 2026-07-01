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


# Promote numeric SOCT_COMPILE_DEFS to linker symbols for linker scripts.
# Non-integer values (quoted strings, ON/OFF, etc.) are skipped because --defsym accepts integers only.
foreach(_def IN LISTS SOCT_COMPILE_DEFS)
    if(_def MATCHES "^([A-Za-z_][A-Za-z0-9_]*)=([0-9]+)$")
        list(APPEND _link_defs "LINKER:--defsym=${_def}")
    endif()
endforeach()
add_link_options(${_link_defs})


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
    set(_reset_wrapper "${CMAKE_BINARY_DIR}/${_rst_tgt}.py")

    # TCL commands sent to xsdb (one per line — used for remote stdin pipe and local temp file)
    set(_reset_tcl_lines "connect\ntargets -set -filter {name =~ {Hart #0*}}\nrst -system\ndisconnect\n")

    if (SOCT_FLASH_HOST)
        # Remote: pipe the Tcl to xsdb which is the login shell on the remote host.
        set(_reset_content "\
import subprocess

host = '${SOCT_FLASH_HOST}'
xsdb = '${SOCT_FLASH_XSDB}'
tcl  = [
    'connect',
    'targets -set -filter {name =~ {Hart #0*}}',
    'rst -system',
    'disconnect',
]

print(f'[reset-core] triggering on {host} via xsdb...')
subprocess.run(['ssh', host, xsdb], input='\\n'.join(tcl).encode(), check=True)
print('[reset-core] done.')
")
    else ()
        set(_reset_content "\
import subprocess, tempfile, os

xsdb = '${SOCT_FLASH_XSDB}'
tcl  = '\\n'.join([
    'connect',
    'targets -set -filter {name =~ {Hart #0*}}',
    'rst -system',
    'disconnect',
])

print('[reset-core] triggering via xsdb...')
with tempfile.NamedTemporaryFile(mode='w', suffix='.tcl', delete=False) as f:
    f.write(tcl)
    tmp = f.name
try:
    subprocess.run([xsdb, tmp], stdin=subprocess.DEVNULL, check=True)
finally:
    os.unlink(tmp)
print('[reset-core] done.')
")
    endif ()

    file(GENERATE OUTPUT "${_reset_wrapper}" CONTENT "${_reset_content}")

    add_custom_target(${_rst_tgt}
            COMMAND ${CMAKE_COMMAND} -E env python3 "${_reset_wrapper}"
            USES_TERMINAL
            VERBATIM
            COMMENT "Triggering ${_rst_tgt} via xsdb")
endif ()
