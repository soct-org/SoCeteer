cmake_minimum_required(VERSION 3.21)

# Gemmini programs only exist on Gemmini designs: the workspace carries the
# config-matched gemmini-rocc-tests headers (gemmini_params.h is generated from the
# elaborated accelerator). Skip cleanly elsewhere so `all` still builds.
if (NOT EXISTS "${SOCT_SYSTEM_ROOT}/gemmini-rocc-tests")
    message(STATUS "gemmini-mnist: skipped - this design has no Gemmini (no gemmini-rocc-tests in the workspace)")
    return ()
endif ()

list(APPEND CMAKE_C_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.c)

include(${CMAKE_CURRENT_LIST_DIR}/../userspace.cmake)

target_include_directories(${SOCT_PROGRAM} PRIVATE
        ${SOCT_SYSTEM_ROOT}/gemmini-rocc-tests
        # The model, sample data and contract shared with the bare-metal variant
        # (binaries/baremetal/gemmini-mnist).
        ${CMAKE_CURRENT_LIST_DIR}/../../../../shared/mnist
)
