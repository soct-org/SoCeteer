cmake_minimum_required(VERSION 3.20)

# Runs on the Gemmini accelerator; its datatype contract is the gemmini-rocc-tests copy
# the generator places in Gemmini designs' workspaces. Without it there is no accelerator
# to target - skipped rather than breaking that design's `all` build.
if (NOT EXISTS "${SOCT_SYSTEM_ROOT}/gemmini-rocc-tests/include/gemmini_params.h")
    message(STATUS "gemmini-basic: design has no Gemmini (no gemmini-rocc-tests in the workspace) - target skipped")
    return()
endif ()

list(APPEND CMAKE_C_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.c)

include(${CMAKE_CURRENT_LIST_DIR}/../soctglue-static.cmake)

######################################################################
# Apply Project specific flags - Modify this section to add more flags
######################################################################
target_compile_options(${SOCT_PROGRAM} PRIVATE -std=gnu11)
target_include_directories(${SOCT_PROGRAM} PRIVATE
        ${SOCT_SYSTEM_ROOT}/gemmini-rocc-tests
)