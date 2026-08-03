cmake_minimum_required(VERSION 3.20)

# The minimal monitor-drawing program: soctfb brings the pipeline up, main.c
# draws. Copy this directory to start an own one.
include(${CMAKE_CURRENT_LIST_DIR}/../soctfb/soctfb.cmake)

list(APPEND CMAKE_C_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.c)

include(${CMAKE_CURRENT_LIST_DIR}/../soctglue-static.cmake)

target_compile_options(${SOCT_PROGRAM} PRIVATE -std=gnu11 -O2)

soctfb_apply(${SOCT_PROGRAM})
