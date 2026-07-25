cmake_minimum_required(VERSION 3.20)

list(APPEND CMAKE_C_SRCS
        ${CMAKE_CURRENT_SOURCE_DIR}/main.c
        ${CMAKE_CURRENT_SOURCE_DIR}/diag.c
        ${CMAKE_CURRENT_SOURCE_DIR}/dp.c
        ${CMAKE_CURRENT_SOURCE_DIR}/render.c
        ${CMAKE_CURRENT_SOURCE_DIR}/video.c
        ${CMAKE_CURRENT_SOURCE_DIR}/soct_xil/soct_xil.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xdppsu.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xdppsu_spm.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xdppsu_serdes.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xdppsu_edid.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xavbuf.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xavbuf_videoformats.c
)

# The DP pipeline needs the PS initialized (FPD clocks, DP SERDES, the
# S_AXI_LPD port's AFIFM registers) before its first register access -
# otherwise that access stalls the bus.
include(SoctPsuInit)

include(${CMAKE_CURRENT_LIST_DIR}/../soctglue-static.cmake)

######################################################################
# Apply Project specific flags - Modify this section to add more flags
######################################################################
target_compile_options(${SOCT_PROGRAM} PRIVATE -std=gnu11 -O3 -ffast-math)

target_include_directories(${SOCT_PROGRAM} PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/soct_xil
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx
)
# The vendored Xilinx sources (xilinx/) are kept verbatim; silence the warning
# classes they trip so our own code stays -Wall -Wextra clean.
set_source_files_properties(
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xdppsu.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xdppsu_spm.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xdppsu_serdes.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xdppsu_edid.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xavbuf.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xavbuf_videoformats.c
        PROPERTIES COMPILE_OPTIONS "-Wno-unused-parameter;-Wno-sign-compare;-Wno-maybe-uninitialized"
)
