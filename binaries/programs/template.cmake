cmake_minimum_required(VERSION 3.20)

# This file can be included from each program's CMakeLists.txt file to set up. Feel free to just copy-paste and modify
# Input sources: CMAKE_CXX_SRCS, CMAKE_C_SRCS
# Requires SOCT_SYSTEM

################################################################################################

if (NOT DEFINED SOCT_LIBC)
    set(SOCT_LIBC c_nano)
endif ()

if (NOT DEFINED SOCT_LIBCXX)
    set(SOCT_LIBCXX supc++ stdc++)
endif ()

if (NOT DEFINED SOCT_LD_SCRIPT)
    set(SOCT_LD_SCRIPT ${SOCTGLUE_DIR}/soct.ld)
endif ()

get_filename_component(SOCT_PROGRAM ${CMAKE_CURRENT_SOURCE_DIR} NAME)

if (DEFINED SOCT_PROGRAM_PREFIX)
    set(SOCT_PROGRAM ${SOCT_PROGRAM_PREFIX}${SOCT_PROGRAM})
endif ()

if (DEFINED SOCT_PROGRAM_SUFFIX)
    message(STATUS "Adding suffix ${SOCT_PROGRAM_SUFFIX} to program ${SOCT_PROGRAM}")
    set(SOCT_PROGRAM ${SOCT_PROGRAM}${SOCT_PROGRAM_SUFFIX})
endif ()

add_executable(${SOCT_PROGRAM} ${CMAKE_CXX_SRCS} ${CMAKE_C_SRCS})


list(LENGTH CMAKE_CXX_SRCS _cpp_count)
if (_cpp_count EQUAL 0)
    set_target_properties(${SOCT_PROGRAM} PROPERTIES LINKER_LANGUAGE C)
    set(SOCT_PROGRAM_IS_CXX false)
    message(STATUS "Adding C program ${SOCT_PROGRAM}")
else ()
    set_target_properties(${SOCT_PROGRAM} PROPERTIES LINKER_LANGUAGE CXX)
    set(SOCT_PROGRAM_IS_CXX true)
    message(STATUS "Adding C++ program ${SOCT_PROGRAM}")
endif ()

target_link_options(${SOCT_PROGRAM} PRIVATE
        # Remove unused sections to reduce the final binary size
        "LINKER:--gc-sections"
        # Wrap standard library functions to enable float printing etc.
        "LINKER:--wrap=puts"
        "LINKER:--wrap=printf"
        "LINKER:--wrap=vprintf"
        "LINKER:--wrap=sprintf"
        "LINKER:--wrap=snprintf"
        "LINKER:--wrap=fopen"
        "LINKER:--wrap=fopen64"
        "LINKER:--wrap=freopen"
        "LINKER:--wrap=freopen64"
)


# Common options for both C and C++
set(_common_compile_opts
        -march=${SOCT_ARCH}
        -mabi=${SOCT_ABI}
        -mcmodel=medany
        -nostartfiles
        -nodefaultlibs
        -fno-common
        -ffunction-sections
        -fdata-sections
        -Wall
        -Wextra
        -g0
)

set(_common_link_opts
        -march=${SOCT_ARCH}
        -mabi=${SOCT_ABI}
        -T ${SOCT_LD_SCRIPT}
        -mcmodel=medany
        -static
        -nostartfiles
        -nodefaultlibs
        -fno-common
        -Wall
        -Wextra
)


target_compile_options(${SOCT_PROGRAM} PRIVATE ${_common_compile_opts})
target_link_options(${SOCT_PROGRAM} PRIVATE ${_common_link_opts})
target_compile_definitions(${SOCT_PROGRAM} PRIVATE BAREMETAL)

if (SOCT_PROGRAM_IS_CXX)
    set(LIBS_TO_LINK soctglue ${SOCT_LIBC} ${SOCT_LIBCXX} m gcc)
    target_compile_options(${SOCT_PROGRAM} PRIVATE
            -fno-exceptions
            -fno-rtti
            -fno-use-cxa-atexit
    )
    target_link_options(${SOCT_PROGRAM} PRIVATE
            -fno-exceptions
            -fno-rtti
            -fno-use-cxa-atexit
    )
else ()
    set(LIBS_TO_LINK soctglue ${SOCT_LIBC} m gcc)
endif ()

if (CMAKE_VERSION VERSION_GREATER_EQUAL "3.24")
    target_link_libraries(${SOCT_PROGRAM} PRIVATE
            "$<LINK_GROUP:RESCAN,${LIBS_TO_LINK}>"
    )
else ()
    target_link_libraries(${SOCT_PROGRAM} PRIVATE
            -Wl,--start-group
            ${LIBS_TO_LINK}
            -Wl,--end-group
    )
endif ()

target_include_directories(${SOCT_PROGRAM} PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}
)

set_target_properties(${SOCT_PROGRAM} PROPERTIES
        OUTPUT_NAME ${SOCT_PROGRAM}.elf
        RUNTIME_OUTPUT_DIRECTORY ${SOCT_ELFS_DIR}
        LINK_DEPENDS ${SOCT_LD_SCRIPT}
)

# ---- Static stack-usage checking --------------------------------------
# -fstack-usage emits a .su file per translation unit (function, frame bytes,
# qualifier); -Wstack-usage warns at compile time for any single function whose
# frame exceeds the threshold. This catches oversized static frames (large
# local arrays etc.) - it does NOT bound call-chain depth, recursion, or
# indirect calls (GCC marks such frames "dynamic"/"bounded" in the .su files).
# The per-hart stack is only __stack_size bytes (see soct.ld), so warn well
# below it. Override the threshold with -DSOCT_STACK_WARN=<bytes>.
if (NOT DEFINED SOCT_STACK_WARN)
    set(SOCT_STACK_WARN 2048)
endif ()
target_compile_options(${SOCT_PROGRAM} PRIVATE
        -fstack-usage
        -Wstack-usage=${SOCT_STACK_WARN}
)

# Prints the largest static stack frames of this program (from the .su files)
add_custom_target(${SOCT_PROGRAM}-stack-report
        COMMAND sh -c "echo 'Largest static stack frames of ${SOCT_PROGRAM} (bytes, qualifier):' && find ${CMAKE_CURRENT_BINARY_DIR}/CMakeFiles/${SOCT_PROGRAM}.dir -name '*.su' -exec cat {} + | sort -k2 -rn | head -25"
        DEPENDS ${SOCT_PROGRAM}
        VERBATIM
        COMMENT "Static stack usage report for ${SOCT_PROGRAM}"
)

add_custom_target(${SOCT_PROGRAM}-info ALL
        COMMAND ${CMAKE_OBJDUMP} -D -M numeric,no-aliases $<TARGET_FILE:${SOCT_PROGRAM}> > ${SOCT_ELFS_DIR}/${SOCT_PROGRAM}.objdump
        COMMAND ${CMAKE_NM} --size-sort --print-size $<TARGET_FILE:${SOCT_PROGRAM}> > ${SOCT_ELFS_DIR}/${SOCT_PROGRAM}.nm
        DEPENDS ${SOCT_PROGRAM}
        BYPRODUCTS ${SOCT_ELFS_DIR}/${SOCT_PROGRAM}.objdump ${SOCT_ELFS_DIR}/${SOCT_PROGRAM}.nm
        COMMENT "Generating objdump and nm info for ${SOCT_PROGRAM} at ${SOCT_ELFS_DIR}/${SOCT_PROGRAM}.{objdump,nm}"
        VERBATIM
)

# ---- flash target -------------------------------------------------------
# Flashes the ELF to an FPGA via xsdb. Works whether xsdb is local or is
# the remote login shell (piping Tcl commands to ssh stdin).
#
#   SOCT_FLASH_XSDB              Path to xsdb                                [required for local]
#   SOCT_FLASH_BOOT_HART         Boot hart index, passed as a0               [default: 0]
#   SOCT_FLASH_BOOTROM_DTB_ADDR  DTB base address, passed as a1              [default: 0x00010080]
#
# Remote mode (SOCT_FLASH_HOST):
#   SOCT_FLASH_HOST              SSH/SCP host, e.g. "mainframe"
#   SOCT_FLASH_REMOTE_DIR        Remote directory to upload the ELF into     [default: /tmp]
#
# SOCT_FLASH_PRELUDE: Python source a program can set (before including this
# template) to run extra steps before the ELF is flashed - e.g. board or PS
# initialization. It is injected into the generated flash wrapper after the
# header, so it runs with these variables in scope:
#   elf                          Path to the local ELF being flashed
#   xsdb                         Path to xsdb (local binary, or on the remote host)
#   host                         SSH host, or '' when flashing locally
#   remote_dir                   Remote upload directory ('' when local)
# The wrapper's own imports (subprocess, sys, os) are available.

if (NOT DEFINED SOCT_FLASH_HOST)
    set(SOCT_FLASH_HOST "" CACHE STRING "SSH/SCP host for flashing — leave empty to flash locally")
endif ()
if (NOT DEFINED SOCT_FLASH_XSDB)
    set(SOCT_FLASH_XSDB "" CACHE STRING "Path to xsdb (local binary, or path on the remote host)")
endif ()
if (NOT DEFINED SOCT_FLASH_REMOTE_DIR)
    set(SOCT_FLASH_REMOTE_DIR "/tmp" CACHE STRING "Remote directory to upload ELF files into (remote mode only)")
endif ()
if (NOT DEFINED SOCT_FLASH_BOOT_HART)
    set(SOCT_FLASH_BOOT_HART "0" CACHE STRING "Boot hart index passed as a0 to xsdb")
endif ()
if (NOT DEFINED SOCT_FLASH_BOOTROM_DTB_ADDR)
    set(SOCT_FLASH_BOOTROM_DTB_ADDR "0x00010080" CACHE STRING "Bootrom DTB base address passed as a1 to xsdb")
endif ()
if (NOT DEFINED SOCT_FLASH_PRELUDE)
    set(SOCT_FLASH_PRELUDE "")
endif ()

if (SOCT_FLASH_HOST OR SOCT_FLASH_XSDB)
    set(_flash_wrapper "${CMAKE_CURRENT_BINARY_DIR}/${SOCT_PROGRAM}-flash.py")

    if (SOCT_FLASH_HOST)
        # Remote: scp the ELF then pipe Tcl to xsdb (which is the login shell).
        set(_flash_wrapper_content "\
import subprocess, sys, os

host       = '${SOCT_FLASH_HOST}'
xsdb       = '${SOCT_FLASH_XSDB}'
remote_dir = '${SOCT_FLASH_REMOTE_DIR}'
boot_hart  = '${SOCT_FLASH_BOOT_HART}'
dtb_addr   = '${SOCT_FLASH_BOOTROM_DTB_ADDR}'
elf        = sys.argv[1]
remote_elf = remote_dir + '/' + os.path.basename(elf)

${SOCT_FLASH_PRELUDE}
print(f'[flash] uploading {elf} to {host}:{remote_dir}/')
subprocess.run(['scp', elf, f'{host}:{remote_dir}/'], check=True)

tcl = [
    'connect',
    'targets -set -filter {name =~ {Hart #0*}}',
    'stop',
    f'dow -clear {remote_elf}',
    f'rwr a0 {boot_hart}',
    f'rwr a1 {dtb_addr}',
    'con',
]
print(f'[flash] flashing on {host} via xsdb...')
subprocess.run(['ssh', host, xsdb], input='\\n'.join(tcl).encode(), check=True)
print('[flash] done.')
")
    else ()
        # Local: invoke xsdb directly with -eval.
        set(_flash_wrapper_content "\
import subprocess, sys, os

host       = ''
remote_dir = ''
xsdb       = '${SOCT_FLASH_XSDB}'
boot_hart  = '${SOCT_FLASH_BOOT_HART}'
dtb_addr   = '${SOCT_FLASH_BOOTROM_DTB_ADDR}'
elf        = sys.argv[1]

${SOCT_FLASH_PRELUDE}
tcl = (
    f'connect; targets -set -filter {{name =~ {{Hart #0*}}}}; stop; '
    f'dow -clear {elf}; rwr a0 {boot_hart}; rwr a1 {dtb_addr}; con'
)
print(f'[flash] running xsdb on {elf}...')
subprocess.run([xsdb, '-eval', tcl], check=True)
print('[flash] done.')
")
    endif ()

    file(GENERATE
            OUTPUT "${_flash_wrapper}"
            CONTENT "${_flash_wrapper_content}")

    # CROSSCOMPILING_EMULATOR is used by ctest / cmake --build --target run.
    # We prepend python3 so it works on all platforms.
    set_target_properties(${SOCT_PROGRAM} PROPERTIES
            CROSSCOMPILING_EMULATOR "python3;${_flash_wrapper}")

    add_custom_target(${SOCT_PROGRAM}-flash
            COMMAND ${CMAKE_COMMAND} -E env python3 "${_flash_wrapper}" $<TARGET_FILE:${SOCT_PROGRAM}>
            DEPENDS ${SOCT_PROGRAM}
            USES_TERMINAL
            VERBATIM
            COMMENT "Flashing ${SOCT_PROGRAM}.elf via xsdb")
endif ()
