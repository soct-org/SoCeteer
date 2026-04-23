cmake_minimum_required(VERSION 3.20)

# This file can be included from each program's CMakeLists.txt file to set up. Feel free to just copy-paste and modify
# Input sources: CMAKE_CXX_SRCS, CMAKE_C_SRCS
# Requires SOCT_SYSTEM

set(LIBC c_nano)

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

if (SOCT_TARGET STREQUAL "verilator")
    set(LD_SCRIPT ${LIBGLOSS_DIR}/htif/util/htif.ld)
    set(LGLOSS gloss_htif)
    # This calls the frontend server at the start of the program to fetch arguments for the target available in (argc, argv)
    target_link_options(${SOCT_PROGRAM} PRIVATE
            "LINKER:--defsym=_start_main=_start_main_argv"
            "LINKER:--defsym=_start_secondary=_start_secondary_argv"
            "LINKER:--wrap=puts"
            "LINKER:--wrap=printf"
            "LINKER:--wrap=sprintf"
            "LINKER:--wrap=snprintf"
            "LINKER:--gc-sections"
    )
elseif (SOCT_TARGET STREQUAL "vivado")
    set(LD_SCRIPT ${LIBGLOSS_DIR}/board/util/board.ld)
    set(LGLOSS gloss_board)
else ()
    message(FATAL_ERROR "Unknown SOCT_TARGET: ${SOCT_TARGET}.")
endif ()


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
        -T ${LD_SCRIPT}
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
    set(LIBS_TO_LINK ${LGLOSS} ${LIBC} supc++ stdc++ m gcc)
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
    set(LIBS_TO_LINK ${LGLOSS} ${LIBC} m gcc)
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

add_custom_target(objdump-${SOCT_PROGRAM} ALL
        COMMAND ${CMAKE_OBJDUMP} -D -M numeric,no-aliases $<TARGET_FILE:${SOCT_PROGRAM}> > ${SOCT_ELFS_DIR}/${SOCT_PROGRAM}.objdump
        DEPENDS ${SOCT_PROGRAM}
        BYPRODUCTS ${SOCT_ELFS_DIR}/${SOCT_PROGRAM}.objdump
        COMMENT "Generating objdump for ${SOCT_PROGRAM} at ${SOCT_ELFS_DIR}/${SOCT_PROGRAM}.objdump"
        VERBATIM
)