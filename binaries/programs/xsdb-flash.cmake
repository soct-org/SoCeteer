# Flashes the ELF to an FPGA via xsdb. Works whether xsdb is local or is
# the remote login shell (piping Tcl commands to ssh stdin).
#   SOCT_PROGRAM                 The program to generate the wrapper for     [required]
#   SOCT_FLASH_XSDB              Path to xsdb                                [required for local]
#   SOCT_FLASH_BOOT_HART         Boot hart index, passed as a0               [default: 0]
#
# Remote mode (SOCT_FLASH_HOST):
#   SOCT_FLASH_HOST              SSH/SCP host, e.g. "mainframe"
#   SOCT_FLASH_REMOTE_DIR        Remote directory to upload the ELF into     [default: /tmp]
#
# SOCT_FLASH_PRELUDE: Python source a program can set (before including this
# template) to run extra steps before the ELF is flashed - e.g. board or PS
# initialization. It is injected into the generated flash wrapper after the
# header, so it runs with these variables in scope:
#   elf                          Path to the local ELF being flashed
#   xsdb                         Path to xsdb (local binary, or on the remote host)
#   host                         SSH host, or '' when flashing locally
#   remote_dir                   Remote upload directory ('' when local)
# The wrapper's own imports (subprocess, sys, os) are available.

if (NOT DEFINED SOCT_FLASH_HOST)
    set(SOCT_FLASH_HOST "" CACHE STRING "SSH/SCP host for flashing — leave empty to flash locally")
endif ()
if (NOT DEFINED SOCT_FLASH_XSDB)
    set(SOCT_FLASH_XSDB "" CACHE STRING "Path to xsdb (local binary, or path on the remote host)")
endif ()
if (NOT DEFINED SOCT_FLASH_REMOTE_DIR)
    set(SOCT_FLASH_REMOTE_DIR "/tmp" CACHE STRING "Remote directory to upload ELF files into (remote mode only)")
endif ()
if (NOT DEFINED SOCT_FLASH_BOOT_HART)
    set(SOCT_FLASH_BOOT_HART "0" CACHE STRING "Boot hart index passed as a0 to xsdb")
endif ()
if (NOT DEFINED SOCT_FLASH_PRELUDE)
    set(SOCT_FLASH_PRELUDE "")
endif ()

if (SOCT_FLASH_HOST OR SOCT_FLASH_XSDB)
    set(_flash_wrapper "${CMAKE_CURRENT_BINARY_DIR}/${SOCT_PROGRAM}-flash.py")

    if (SOCT_FLASH_HOST)
        # Remote: scp the ELF then pipe Tcl to xsdb (which is the login shell).
        set(_flash_wrapper_content "\
import subprocess, sys, os

host       = '${SOCT_FLASH_HOST}'
xsdb       = '${SOCT_FLASH_XSDB}'
remote_dir = '${SOCT_FLASH_REMOTE_DIR}'
boot_hart  = '${SOCT_FLASH_BOOT_HART}'
dtb_addr   = '${SOCT_DTB_ADDR}'
elf        = sys.argv[1]
remote_elf = remote_dir + '/' + os.path.basename(elf)

${SOCT_FLASH_PRELUDE}
print(f'[flash] uploading {elf} to {host}:{remote_dir}/')
subprocess.run(['scp', elf, f'{host}:{remote_dir}/'], check=True)

tcl = [
    'connect',
    'targets -set -filter {name =~ {Hart #0*}}',
    'stop',
    f'dow -clear {remote_elf}',
    f'rwr a0 {boot_hart}',
    f'rwr a1 {dtb_addr}',
    'con',
]
print(f'[flash] flashing on {host} via xsdb...')
subprocess.run(['ssh', host, xsdb], input='\\n'.join(tcl).encode(), check=True)
print('[flash] done.')
")
    else ()
        # Local: invoke xsdb directly with -eval.
        set(_flash_wrapper_content "\
import subprocess, sys, os

host       = ''
remote_dir = ''
xsdb       = '${SOCT_FLASH_XSDB}'
boot_hart  = '${SOCT_FLASH_BOOT_HART}'
dtb_addr   = '${SOCT_DTB_ADDR}'
elf        = sys.argv[1]

${SOCT_FLASH_PRELUDE}
tcl = (
    f'connect; targets -set -filter {{name =~ {{Hart #0*}}}}; stop; '
    f'dow -clear {elf}; rwr a0 {boot_hart}; rwr a1 {dtb_addr}; con'
)
print(f'[flash] running xsdb on {elf}...')
subprocess.run([xsdb, '-eval', tcl], check=True)
print('[flash] done.')
")
    endif ()

    file(GENERATE
            OUTPUT "${_flash_wrapper}"
            CONTENT "${_flash_wrapper_content}")

    # CROSSCOMPILING_EMULATOR is used by ctest / cmake --build --target run.
    # We prepend python3 so it works on all platforms.
    set_target_properties(${SOCT_PROGRAM} PROPERTIES
            CROSSCOMPILING_EMULATOR "python3;${_flash_wrapper}")

    add_custom_target(${SOCT_PROGRAM}-flash
            COMMAND ${CMAKE_COMMAND} -E env python3 "${_flash_wrapper}" $<TARGET_FILE:${SOCT_PROGRAM}>
            DEPENDS ${SOCT_PROGRAM}
            USES_TERMINAL
            VERBATIM
            COMMENT "Flashing ${SOCT_PROGRAM}.elf via xsdb")
endif ()
