cmake_minimum_required(VERSION 3.20)

list(APPEND CMAKE_C_SRCS
        ${CMAKE_CURRENT_SOURCE_DIR}/main.c
        ${CMAKE_CURRENT_SOURCE_DIR}/soct_xil/xil_io.c
        ${CMAKE_CURRENT_SOURCE_DIR}/soct_xil/sleep.c
        ${CMAKE_CURRENT_SOURCE_DIR}/soct_xil/xvidc.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xdppsu.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xdppsu_spm.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xdppsu_serdes.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xdppsu_edid.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xavbuf.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xilinx/xavbuf_videoformats.c
)

# The DP pipeline needs the PS initialized (FPD clocks, DP SERDES, the
# S_AXI_LPD port's AFIFM registers) before its first register access -
# otherwise that access stalls the bus. The prelude runs the Vivado-generated
# psu_init sequence via xsdb before every flash (idempotent; strictly needed
# once per power-cycle). The psu_init.tcl comes from the local workspace's
# Vivado project; there is no robust way to locate one anywhere else.
set(SOCT_FLASH_PRELUDE "\
# --- dp-test prelude: PS initialization (psu_init) --------------------------
import glob

_psu = sorted(glob.glob('${SOCT_SYSTEM_ROOT}/vivado-project/'
                        '*.gen/sources_1/bd/*/ip/*zynq_ultra_ps*/psu_init.tcl'))
if not _psu:
    raise SystemExit('[flash] FATAL: no psu_init.tcl under '
                     '${SOCT_SYSTEM_ROOT}/vivado-project - generate the Vivado '
                     'project first (the PS cannot be initialized without it)')
_psu_path = _psu[0]

if host:
    print('[flash] uploading ' + _psu_path + ' to ' + host + ':' + remote_dir + '/')
    subprocess.run(['scp', _psu_path, host + ':' + remote_dir + '/'], check=True)
    _psu_path = remote_dir + '/' + os.path.basename(_psu_path)

_psu_tcl = [
    'connect',
    'targets -set -filter {name =~ {PSU*}}',
    'source ' + _psu_path,
    'psu_init',
    'after 1000',
    'psu_post_config',
    'psu_ps_pl_isolation_removal',
    'psu_ps_pl_reset_config',
    'disconnect',
]
print('[flash] initializing the PS (psu_init)...')
if host:
    subprocess.run(['ssh', host, xsdb], input='\\n'.join(_psu_tcl).encode(), check=True)
else:
    subprocess.run([xsdb, '-eval', '; '.join(_psu_tcl)], check=True)
print('[flash] psu_init done.')
# -----------------------------------------------------------------------------
")

include(${CMAKE_CURRENT_LIST_DIR}/../template.cmake)

######################################################################
# Apply Project specific flags - Modify this section to add more flags
######################################################################
target_compile_options(${SOCT_PROGRAM} PRIVATE -std=gnu11 -O3)

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
