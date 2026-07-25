# PS initialization for anything flashed over JTAG.
#
# The Zynq UltraScale+ processing system answers no register access, holds its PS-PL interfaces
# isolated and leaves its peripheral PHYs unconfigured until the `psu_init` sequence Vivado
# generates alongside the design has run. That makes it a prerequisite for more than the PS
# peripherals themselves: a design in which a PS master shares fabric with the SD controller
# needs it before that controller works, so it belongs on every program that runs on such a
# design, not only on the ones that talk to the PS.
#
# Include this before soct_xsdb_flash_target() and every flash of that program runs the
# sequence first:
#
#     include(SoctPsuInit)
#     include(SoctXsdbFlash)
#     soct_xsdb_flash_target(...)
#
# Including it also creates one build-tree-wide `psu-init` target (whichever program includes
# the module first creates it) that runs ONLY the sequence, flashing nothing - for bringing the
# PS up before software flashed by other means, and for recovering a power cycle in which it
# was never run.
#
# CAVEAT - running it TWICE in one power cycle is not free: on a processing system that is
# already up, re-running psu_init leaves the DisplayPort controller wedged until the board is
# power-cycled. The sequence is otherwise idempotent. Power-cycle between flashing two programs
# that both carry this prelude if the second one needs video.
#
# psu_init.tcl is generated with the block design, from the processing system's configuration,
# so it exists only where the design was built - there is no copy in a Vivado installation and
# no board file that stands in for it.

set(SOCT_FLASH_PRELUDE "\
# --- prelude: PS initialization (psu_init) ----------------------------------
import glob

_psu = sorted(glob.glob('${SOCT_SYSTEM_ROOT}/vivado-project/'
                        '*.gen/sources_1/bd/*/ip/*zynq_ultra_ps*/psu_init.tcl'))
if not _psu:
    raise SystemExit('[flash] FATAL: no psu_init.tcl under ${SOCT_SYSTEM_ROOT}/vivado-project; '
                     'the processing system cannot be initialized without it. It is generated '
                     'with the block design, so it exists only where the design was built: '
                     'generate the Vivado project here, or pass --sfr to sync it back from the '
                     'machine that built it.')
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

# --- standalone `psu-init` target -------------------------------------------
# Mirrors the flash wrappers' transport setup (SoctXsdbFlash defines the same cache variables;
# defining them here too keeps this module usable whichever is included first). Created once per
# build tree, and only when a way to reach xsdb is configured - without one there is nothing the
# target could run.
if (NOT DEFINED SOCT_FLASH_HOST)
    set(SOCT_FLASH_HOST "" CACHE STRING "SSH/SCP host for flashing — leave empty to flash locally")
endif ()
if (NOT DEFINED SOCT_FLASH_XSDB)
    set(SOCT_FLASH_XSDB "" CACHE STRING "Path to xsdb (local binary, or path on the remote host)")
endif ()
if (NOT DEFINED SOCT_FLASH_REMOTE_DIR)
    set(SOCT_FLASH_REMOTE_DIR "/tmp" CACHE STRING "Remote directory to upload ELF files into (remote mode only)")
endif ()

if ((SOCT_FLASH_HOST OR SOCT_FLASH_XSDB) AND NOT TARGET psu-init)
    set(_psu_init_wrapper "${CMAKE_BINARY_DIR}/psu-init.py")
    if (SOCT_FLASH_HOST)
        set(_psu_init_header "\
import subprocess, sys, os

host       = '${SOCT_FLASH_HOST}'
xsdb       = '${SOCT_FLASH_XSDB}'
remote_dir = '${SOCT_FLASH_REMOTE_DIR}'
")
    else ()
        set(_psu_init_header "\
import subprocess, sys, os

host       = ''
remote_dir = ''
xsdb       = '${SOCT_FLASH_XSDB}'
")
    endif ()
    file(GENERATE
            OUTPUT "${_psu_init_wrapper}"
            CONTENT "${_psu_init_header}\n${SOCT_FLASH_PRELUDE}")
    add_custom_target(psu-init
            COMMAND ${CMAKE_COMMAND} -E env python3 "${_psu_init_wrapper}"
            USES_TERMINAL
            VERBATIM
            COMMENT "Initializing the processing system (psu_init) via xsdb")
endif ()
