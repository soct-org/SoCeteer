cmake_minimum_required(VERSION 3.20)

# The pipeline plumbing (framebuffers, VDMA, VTC, DP link, vendored Xilinx
# drivers and their bare-metal shims) lives in the soctfb library; this test
# keeps its own bring-up flow on top of it, with diagnostics between the steps.
include(${CMAKE_CURRENT_LIST_DIR}/../soctfb/soctfb.cmake)

list(APPEND CMAKE_C_SRCS
        ${CMAKE_CURRENT_SOURCE_DIR}/main.c
        ${CMAKE_CURRENT_SOURCE_DIR}/diag.c
        ${CMAKE_CURRENT_SOURCE_DIR}/render.c
)

include(${CMAKE_CURRENT_LIST_DIR}/../soctglue-static.cmake)

######################################################################
# Apply Project specific flags - Modify this section to add more flags
######################################################################
target_compile_options(${SOCT_PROGRAM} PRIVATE -std=gnu11 -O3 -ffast-math)

soctfb_apply(${SOCT_PROGRAM})
