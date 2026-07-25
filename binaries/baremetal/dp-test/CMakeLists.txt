cmake_minimum_required(VERSION 3.20)

# The vendored Xilinx DisplayPort drivers, shared with the soct-dp kernel module
# (binaries/linux/drivers/dp); this program compiles them against its bare-metal
# shims in soct_xil/.
set(SOCT_XILINX_DP "${SOCETEER_ROOT}/shared/vendor/xilinx-dp")

list(APPEND CMAKE_C_SRCS
        ${CMAKE_CURRENT_SOURCE_DIR}/main.c
        ${CMAKE_CURRENT_SOURCE_DIR}/diag.c
        ${CMAKE_CURRENT_SOURCE_DIR}/dp.c
        ${CMAKE_CURRENT_SOURCE_DIR}/render.c
        ${CMAKE_CURRENT_SOURCE_DIR}/video.c
        ${CMAKE_CURRENT_SOURCE_DIR}/soct_xil/soct_xil.c
        ${SOCT_XILINX_DP}/xdppsu.c
        ${SOCT_XILINX_DP}/xdppsu_spm.c
        ${SOCT_XILINX_DP}/xdppsu_serdes.c
        ${SOCT_XILINX_DP}/xdppsu_edid.c
        ${SOCT_XILINX_DP}/xavbuf.c
        ${SOCT_XILINX_DP}/xavbuf_videoformats.c
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
        ${SOCT_XILINX_DP}
)
# The vendored Xilinx sources are kept verbatim; silence the warning
# classes they trip so our own code stays -Wall -Wextra clean.
set_source_files_properties(
        ${SOCT_XILINX_DP}/xdppsu.c
        ${SOCT_XILINX_DP}/xdppsu_spm.c
        ${SOCT_XILINX_DP}/xdppsu_serdes.c
        ${SOCT_XILINX_DP}/xdppsu_edid.c
        ${SOCT_XILINX_DP}/xavbuf.c
        ${SOCT_XILINX_DP}/xavbuf_videoformats.c
        PROPERTIES COMPILE_OPTIONS "-Wno-unused-parameter;-Wno-sign-compare;-Wno-maybe-uninitialized"
)
