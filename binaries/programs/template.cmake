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


target_include_directories(${SOCT_PROGRAM} PRIVATE ${LIBGLOSS_DIR}/include)

set_target_properties(${SOCT_PROGRAM} PROPERTIES OUTPUT_NAME ${SOCT_PROGRAM}.elf RUNTIME_OUTPUT_DIRECTORY ${SOCT_ELFS_DIR})

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
#   SOCT_FLASH_BOOT_HART         Boot hart index, passed as a0                [default: 0]
#   SOCT_FLASH_BOOTROM_DTB_ADDR  DTB base address, passed as a1               [default: 0x00010080]
#
# Remote mode (SOCT_FLASH_HOST):
#   SOCT_FLASH_HOST              SSH/SCP host, e.g. "mainframe"
#   SOCT_FLASH_REMOTE_DIR        Remote directory to upload the ELF into       [default: /tmp]

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

if (SOCT_FLASH_HOST OR SOCT_FLASH_XSDB)
    set(_flash_wrapper "${CMAKE_CURRENT_BINARY_DIR}/${SOCT_PROGRAM}-flash.sh")
    set(_flash_tcl "connect; targets -set -filter {name =~ {Hart #0*}}; stop; dow -clear")

    if (SOCT_FLASH_HOST)
        # Remote: xsdb is the login shell on the remote host — pipe Tcl commands
        # to ssh stdin rather than using "ssh host shell-command".
        set(_flash_wrapper_content "\
#!/bin/sh
set -e
echo \"[flash] uploading $1 to ${SOCT_FLASH_HOST}:${SOCT_FLASH_REMOTE_DIR}/\"
scp \"$1\" \"${SOCT_FLASH_HOST}:${SOCT_FLASH_REMOTE_DIR}/\"
echo '[flash] flashing on ${SOCT_FLASH_HOST} via xsdb...'
printf 'connect\\ntargets -set -filter {name =~ {Hart #0*}}\\nstop\\ndow -clear ${SOCT_FLASH_REMOTE_DIR}/${SOCT_PROGRAM}.elf\\nrwr a0 ${SOCT_FLASH_BOOT_HART}\\nrwr a1 ${SOCT_FLASH_BOOTROM_DTB_ADDR}\\ncon\\n' | ssh \"${SOCT_FLASH_HOST}\" \"${SOCT_FLASH_XSDB}\"
")
    else ()
        # Local: run xsdb directly, passing Tcl via -eval.
        set(_flash_wrapper_content "\
#!/bin/sh
set -e
echo '[flash] running xsdb on $1...'
\"${SOCT_FLASH_XSDB}\" -eval \"${_flash_tcl} $1; rwr a0 ${SOCT_FLASH_BOOT_HART}; rwr a1 ${SOCT_FLASH_BOOTROM_DTB_ADDR}; con\"
")
    endif ()

    file(GENERATE
            OUTPUT "${_flash_wrapper}"
            CONTENT "${_flash_wrapper_content}"
            FILE_PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE
            GROUP_READ GROUP_EXECUTE
            WORLD_READ WORLD_EXECUTE)

    set_target_properties(${SOCT_PROGRAM} PROPERTIES CROSSCOMPILING_EMULATOR "${_flash_wrapper}")

    add_custom_target(${SOCT_PROGRAM}-flash
            COMMAND "${_flash_wrapper}" $<TARGET_FILE:${SOCT_PROGRAM}>
            DEPENDS ${SOCT_PROGRAM}
            USES_TERMINAL
            VERBATIM
            COMMENT "Flashing ${SOCT_PROGRAM}.elf via xsdb")
endif ()
