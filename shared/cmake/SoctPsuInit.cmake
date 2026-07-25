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
# The sequence is NOT idempotent: a re-run in the same power cycle takes the DisplayPort
# down and kills the USB host controller on its first transfer - and USB only comes back
# with a power cycle (the controller and its ULPI PHY are in the PS, which the board's
# reset button never touches, and no reset line is wired to the PHY).
#
# The prelude therefore runs the sequence ONCE PER POWER CYCLE, detected rather than
# tracked: after running it, a marker is written to PMU_GLOBAL.PERS_GLOB_GEN_STORAGE0 -
# a PS scratch register cleared by power-on reset and nothing else, which is exactly the
# lifetime of the initialization itself (in particular, the board's reset button clears
# neither the marker nor the PS state it stands for). Every flash first reads the marker
# and skips the sequence when it is present, so re-flashing freely is safe. No PS software
# runs on this system, so nothing else claims the register. To force a re-run anyway
# (knowing what it breaks): xsdb 'mwr -force 0xFFD80050 0', then flash.
#
# psu_init.tcl is generated with the block design, from the processing system's configuration,
# so it exists only where the design was built - there is no copy in a Vivado installation and
# no board file that stands in for it.

set(SOCT_FLASH_PRELUDE "\
# --- prelude: PS initialization (psu_init, once per power cycle) -------------
import glob

# PMU_GLOBAL.PERS_GLOB_GEN_STORAGE0: cleared by power-on reset and nothing else - the same
# lifetime as the PS initialization it marks. See SoctPsuInit.cmake for the whole story.
_PSU_MARKER_ADDR = '0xFFD80050'
_PSU_MARKER_HEX = '50535549'  # 'PSUI'

def _xsdb_run(commands):
    if host:
        return subprocess.run(['ssh', host, xsdb], input='\\n'.join(commands).encode(),
                              check=True, capture_output=True).stdout.decode()
    return subprocess.run([xsdb, '-eval', '; '.join(commands)],
                          check=True, capture_output=True).stdout.decode()

_marker_out = _xsdb_run([
    'connect',
    'targets -set -filter {name =~ {PSU*}}',
    'puts [format %08x [mrd -force -value ' + _PSU_MARKER_ADDR + ']]',
    'disconnect',
])
if _PSU_MARKER_HEX in _marker_out:
    print('[flash] PS already initialized this power cycle - skipping psu_init.')
else:
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

    print('[flash] initializing the PS (psu_init)...')
    _xsdb_run([
        'connect',
        'targets -set -filter {name =~ {PSU*}}',
        'source ' + _psu_path,
        'psu_init',
        'after 1000',
        'psu_post_config',
        'psu_ps_pl_isolation_removal',
        'psu_ps_pl_reset_config',
        'mwr -force ' + _PSU_MARKER_ADDR + ' 0x' + _PSU_MARKER_HEX,
        'disconnect',
    ])
    print('[flash] psu_init done (marker written - later flashes this power cycle skip it).')
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
