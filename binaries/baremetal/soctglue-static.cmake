cmake_minimum_required(VERSION 3.20)

################################################################################################
# This file can be included from each program's CMakeLists.txt file.
# Input sources: CMAKE_CXX_SRCS, CMAKE_C_SRCS
# Variables:
#   SOCT_LIBC
#   SOCT_LIBCXX
#   SOCT_PROGRAM_PREFIX
#   SOCT_PROGRAM_SUFFIX
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
    set(_libs_to_link soctglue ${SOCT_LIBC} ${SOCT_LIBCXX} m gcc)
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
    set(_libs_to_link soctglue ${SOCT_LIBC} m gcc)
endif ()

if (CMAKE_VERSION VERSION_GREATER_EQUAL "3.24")
    target_link_libraries(${SOCT_PROGRAM} PRIVATE
            "$<LINK_GROUP:RESCAN,${_libs_to_link}>"
    )
else ()
    target_link_libraries(${SOCT_PROGRAM} PRIVATE
            -Wl,--start-group
            ${_libs_to_link}
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

add_custom_target(${SOCT_PROGRAM}-info ALL
        COMMAND ${CMAKE_OBJDUMP} -D -M numeric,no-aliases $<TARGET_FILE:${SOCT_PROGRAM}> > ${SOCT_ELFS_DIR}/${SOCT_PROGRAM}.objdump
        COMMAND ${CMAKE_NM} --size-sort --print-size $<TARGET_FILE:${SOCT_PROGRAM}> > ${SOCT_ELFS_DIR}/${SOCT_PROGRAM}.nm
        COMMAND sh -c "echo '------STACK INFO------\\n Largest static stack frames of ${SOCT_PROGRAM} (bytes, qualifier):' && find ${CMAKE_CURRENT_BINARY_DIR}/CMakeFiles/${SOCT_PROGRAM}.dir -name '*.su' -exec cat {} + | sort -k2 -rn | head -25"
        DEPENDS ${SOCT_PROGRAM}
        BYPRODUCTS ${SOCT_ELFS_DIR}/${SOCT_PROGRAM}.objdump ${SOCT_ELFS_DIR}/${SOCT_PROGRAM}.nm
        COMMENT "Generating objdump and nm info for ${SOCT_PROGRAM} at ${SOCT_ELFS_DIR}/${SOCT_PROGRAM}.{objdump,nm}"
        VERBATIM
)

# All SOCT Programs are flashable!
include(${CMAKE_CURRENT_LIST_DIR}/xsdb-flash.cmake)