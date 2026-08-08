cmake_minimum_required(VERSION 3.20)

# Exercises the SD controller through soctglue's disk backend, which only exists on
# designs with the controller - skipped rather than breaking that design's `all` build.
if (NOT DEFINED SOCT_NEEDS_FATFS)
    message(STATUS "sd-test: design has no SD-card controller - target skipped")
    return()
endif ()

list(APPEND CMAKE_C_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.c)

include(${CMAKE_CURRENT_LIST_DIR}/../soctglue-static.cmake)

######################################################################
# Apply Project specific flags - Modify this section to add more flags
######################################################################
target_compile_options(${SOCT_PROGRAM} PRIVATE -std=gnu11 -O3 -Os)
