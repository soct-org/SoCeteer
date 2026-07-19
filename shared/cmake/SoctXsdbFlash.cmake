# JTAG flashing via xsdb, shared by both binaries projects: the bare-metal template
# flashes program ELFs, the linux project flashes boot images (BOOT.ELF is a plain
# physical-address ELF, so booting Linux over JTAG works exactly like loading a
# program - no SD card involved). Works whether xsdb is local or is the remote login
# shell (piping Tcl commands to ssh stdin).
#
# soct_xsdb_flash_target(<name> <elf> [<dependency>...]) creates <name>-flash, which
# loads <elf>, sets a0 = SOCT_FLASH_BOOT_HART and a1 = SOCT_DTB_ADDR (the system
# file's ROM DTB address - the same handoff the boot ROM performs) and continues.
# No target is created when neither SOCT_FLASH_XSDB nor SOCT_FLASH_HOST is set.
# The generated wrapper is <current binary dir>/<name>-flash.py.
#
# SOCT_FLASH_PRELUDE: Python source the CALLER may set before invoking the function -
# extra steps to run before the ELF is flashed (board or subsystem initialization). It
# is injected into the wrapper after the header and runs with these variables in scope:
#   elf          Path to the local ELF being flashed
#   xsdb         Path to xsdb (local binary, or on the remote host)
#   host         SSH host, or '' when flashing locally
#   remote_dir   Remote upload directory ('' when local)
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

function(soct_xsdb_flash_target _flash_name _flash_elf)
    if (NOT SOCT_FLASH_HOST AND NOT SOCT_FLASH_XSDB)
        return ()
    endif ()
    if (NOT DEFINED SOCT_FLASH_PRELUDE)
        set(SOCT_FLASH_PRELUDE "")
    endif ()
    set(_flash_wrapper "${CMAKE_CURRENT_BINARY_DIR}/${_flash_name}-flash.py")

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

    add_custom_target(${_flash_name}-flash
            COMMAND ${CMAKE_COMMAND} -E env python3 "${_flash_wrapper}" "${_flash_elf}"
            DEPENDS ${ARGN}
            USES_TERMINAL
            VERBATIM
            COMMENT "Flashing ${_flash_elf} via xsdb")
endfunction()
