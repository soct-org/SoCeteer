cmake_minimum_required(VERSION 3.20)

list(APPEND CMAKE_CXX_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.cpp)

include(${CMAKE_CURRENT_LIST_DIR}/../template.cmake)

######################################################################
# Apply Project specific flags - Modify this section to add more flags
######################################################################
foreach(PROG ${PROGRAM_SIM_NAMES} ${PROGRAM_BOARD_NAMES})
    target_compile_options(${PROG} PRIVATE -std=gnu++20 -O3 -Wall -Wextra -Wpedantic)
endforeach ()