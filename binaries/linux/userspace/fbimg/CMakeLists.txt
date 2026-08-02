cmake_minimum_required(VERSION 3.21)

list(APPEND CMAKE_C_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.c)

include(${CMAKE_CURRENT_LIST_DIR}/../userspace.cmake)

# The vendored single-header decoder (see shared/vendor/stb/README.md).
target_include_directories(${SOCT_PROGRAM} PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/../../../../shared/vendor/stb)
