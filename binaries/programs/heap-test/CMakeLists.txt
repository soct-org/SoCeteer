cmake_minimum_required(VERSION 3.20)

list(APPEND CMAKE_C_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.c)

include(${CMAKE_CURRENT_LIST_DIR}/../template.cmake)
target_compile_options(${SOCT_PROGRAM} PRIVATE -std=gnu11 -O2)
target_compile_definitions(${SOCT_PROGRAM} PRIVATE SOCT_LIBC_IS_NANO=1)


# Adds a separate program target that links against the full libc instead of the nano version. This will enable larger heap allocations
set(SOCT_PROGRAM_SUFFIX "_full_libc")
set(SOCT_LIBC c)
include(${CMAKE_CURRENT_LIST_DIR}/../template.cmake)
target_compile_definitions(${SOCT_PROGRAM} PRIVATE SOCT_LIBC_IS_NANO=0)
target_compile_options(${SOCT_PROGRAM} PRIVATE -std=gnu11 -O2)
