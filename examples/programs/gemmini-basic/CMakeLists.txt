cmake_minimum_required(VERSION 3.20)

list(APPEND CMAKE_C_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.c)

include(${CMAKE_CURRENT_LIST_DIR}/../template.cmake)

######################################################################
# Apply Project specific flags - Modify this section to add more flags
######################################################################
# Require the Gemmini headers located in the system directory
include(${CMAKE_CURRENT_LIST_DIR}/../requires_system_dir.cmake)

foreach (PROG ${PROGRAM_SIM_NAMES} ${PROGRAM_BOARD_NAMES})
    target_compile_options(${PROG} PRIVATE -std=gnu11)
    target_include_directories(${PROG} PRIVATE
            ${SYSTEM_DIR}/gemmini-rocc-tests
    )
endforeach ()