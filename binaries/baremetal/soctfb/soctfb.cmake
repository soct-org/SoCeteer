# soctfb - the bare-metal framebuffer library (see soctfb.h). Consumed like the
# other source templates:
#
#   include(${CMAKE_CURRENT_LIST_DIR}/../soctfb/soctfb.cmake)   # appends sources
#   list(APPEND CMAKE_C_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.c)
#   include(${CMAKE_CURRENT_LIST_DIR}/../soctglue-static.cmake) # creates the target
#   soctfb_apply(${SOCT_PROGRAM})                               # include dirs + flags
#
# The DisplayPort controller is programmed through the vendored Xilinx drivers
# (shared with the soct-dp kernel module), compiled against the bare-metal shims
# in soct_xil/. Flashing needs the PS initialized first (SoctPsuInit): the DP
# pipeline's first PS register access stalls the bus otherwise.
set(SOCTFB_DIR "${CMAKE_CURRENT_LIST_DIR}")
set(SOCTFB_XILINX_DP "${SOCETEER_ROOT}/shared/vendor/xilinx-dp")

list(APPEND CMAKE_C_SRCS
        ${SOCTFB_DIR}/soctfb.c
        ${SOCTFB_DIR}/video.c
        ${SOCTFB_DIR}/dp.c
        ${SOCTFB_DIR}/soct_xil/soct_xil.c
        ${SOCTFB_XILINX_DP}/xdppsu.c
        ${SOCTFB_XILINX_DP}/xdppsu_spm.c
        ${SOCTFB_XILINX_DP}/xdppsu_serdes.c
        ${SOCTFB_XILINX_DP}/xdppsu_edid.c
        ${SOCTFB_XILINX_DP}/xavbuf.c
        ${SOCTFB_XILINX_DP}/xavbuf_videoformats.c
)

include(SoctPsuInit)

macro(soctfb_apply target)
    target_include_directories(${target} PRIVATE
            ${SOCTFB_DIR}
            ${SOCTFB_DIR}/soct_xil
            ${SOCTFB_XILINX_DP}
    )
    # The vendored Xilinx sources are kept verbatim; silence the warning
    # classes they trip so consumer code stays -Wall -Wextra clean.
    set_source_files_properties(
            ${SOCTFB_XILINX_DP}/xdppsu.c
            ${SOCTFB_XILINX_DP}/xdppsu_spm.c
            ${SOCTFB_XILINX_DP}/xdppsu_serdes.c
            ${SOCTFB_XILINX_DP}/xdppsu_edid.c
            ${SOCTFB_XILINX_DP}/xavbuf.c
            ${SOCTFB_XILINX_DP}/xavbuf_videoformats.c
            PROPERTIES COMPILE_OPTIONS "-Wno-unused-parameter;-Wno-sign-compare;-Wno-maybe-uninitialized"
    )
endmacro()
