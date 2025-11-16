cmake_minimum_required(VERSION 3.20)

list(APPEND CMAKE_C_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.c)

include(${CMAKE_CURRENT_LIST_DIR}/../template.cmake)

######################################################################
# Apply Project specific flags - Modify this section to add more flags
######################################################################
foreach(target ${PROGRAM_SIM_NAMES} ${PROGRAM_BOARD_NAMES})
    target_compile_options(${target} PRIVATE -std=gnu11 -O3 -Os)
endforeach()