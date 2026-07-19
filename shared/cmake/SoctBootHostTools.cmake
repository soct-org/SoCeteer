# Host-tool discovery shared by the Linux-world build directories
# (binaries/linux-sysroot and binaries/boot): the kernel, OpenSBI and musl are external
# make-based builds with host requirements the repo toolchain cannot satisfy.
#
# soct_boot_find_host_tools() sets, in the caller's scope:
#   _path_ok         FALSE when the build path contains ',' or ' ' (see below)
#   _llvm_ok         a RISC-V-capable LLVM (clang + ld.lld) exists
#   _llvm_bindir     one directory with every LLVM tool (kbuild/OpenSBI LLVM=<dir>/)
#   _llvm_lld        absolute ld.lld path (for -fuse-ld=<path> consumers)
#   _make_ok         a GNU make >= 4.0 exists (kernel requirement)
#   _boot_make       the make to use (falls back to plain `make` when !_make_ok -
#                    sufficient for OpenSBI and musl, which tolerate 3.81)
#   _darwin_sed_ok   FALSE on macOS without GNU sed (kernel generator scripts need it)
#   _khostflags      HOSTCFLAGS=... for kernel host tools (Darwin shims; empty elsewhere)
#   _kenv            command prefix exporting the PATH shim (sed->gsed) on Darwin
# and defines soct_boot_blocked(name reason remedy): a target that exists but fails
# with the exact reason and remedy - so nothing else in binaries/ is ever blocked.
#
# Overrides (cache variables, validated rather than trusted):
#   SOCT_BOOT_LLVM_DIR   LLVM bin directory
#   SOCT_BOOT_MAKE       GNU make binary

set(SOCT_BOOT_LLVM_DIR "" CACHE PATH
        "LLVM bin directory (clang, ld.lld, llvm-*) for the kernel/OpenSBI/musl builds; empty = auto-detect via FindLLVMRiscv")
set(SOCT_BOOT_MAKE "" CACHE STRING
        "GNU make for the kernel/OpenSBI/musl builds (kernel needs >= 4.0); empty = auto-detect via FindGNUMake")

# A target that exists but explains exactly why it cannot run on this host.
function(soct_boot_blocked name reason remedy)
    add_custom_target(${name}
            COMMAND ${CMAKE_COMMAND} -E echo "${name}: ${reason}"
            COMMAND ${CMAKE_COMMAND} -E echo "${name}: ${remedy}"
            COMMAND ${CMAKE_COMMAND} -E false
            COMMENT "${name} is unavailable on this host"
            VERBATIM)
endfunction()

macro(soct_boot_find_host_tools)
    # The kernel and OpenSBI builds cannot live under every path: OpenSBI passes its
    # linker scripts as -Wl,-T<path>, and the compiler driver splits -Wl arguments at
    # EVERY comma - a ',' anywhere in the build path shatters the linker command line.
    # GNU make in turn cannot cope with ' ' in paths. Refuse such directories up front.
    if ("${CMAKE_CURRENT_BINARY_DIR}" MATCHES "[, ]")
        set(_path_ok FALSE)
        message(STATUS "boot: build directory '${CMAKE_CURRENT_BINARY_DIR}' contains ',' or ' ' - the kernel/OpenSBI/musl targets will refuse (their makefiles cannot handle such paths); use a build directory without them, e.g. rename the IDE build profile")
    else ()
        set(_path_ok TRUE)
    endif ()

    if (SOCT_BOOT_LLVM_DIR)
        # Explicit override: must be a complete directory (clang + ld.lld); still prove it, loudly.
        execute_process(
                COMMAND "${SOCT_BOOT_LLVM_DIR}/clang" --target=riscv64-unknown-elf -fPIE -nostdlib
                "--ld-path=${SOCT_BOOT_LLVM_DIR}/ld.lld" -Wl,-pie -x c /dev/null
                -o "${CMAKE_CURRENT_BINARY_DIR}/llvm-probe"
                RESULT_VARIABLE _llvm_probe OUTPUT_QUIET ERROR_QUIET)
        file(REMOVE "${CMAKE_CURRENT_BINARY_DIR}/llvm-probe")
        if (_llvm_probe EQUAL 0)
            set(_llvm_ok TRUE)
            set(_llvm_bindir "${SOCT_BOOT_LLVM_DIR}")
            set(_llvm_lld "${SOCT_BOOT_LLVM_DIR}/ld.lld")
        else ()
            set(_llvm_ok FALSE)
            message(STATUS "boot: SOCT_BOOT_LLVM_DIR='${SOCT_BOOT_LLVM_DIR}' cannot link a RISC-V PIE through its ld.lld - the LLVM-based targets will refuse")
        endif ()
    else ()
        find_package(LLVMRiscv QUIET)
        if (LLVMRiscv_FOUND)
            set(_llvm_ok TRUE)
            set(_llvm_bindir "${LLVMRISCV_BIN_DIR}")
            set(_llvm_lld "${LLVMRISCV_LLD}")
            message(STATUS "boot: using LLVM/Clang ${LLVMRISCV_VERSION} at ${LLVMRISCV_BIN_DIR}")
        else ()
            set(_llvm_ok FALSE)
            message(STATUS "boot: no RISC-V-capable LLVM found (clang + ld.lld) - the LLVM-based targets will refuse; install one (macOS: brew install llvm lld; Debian: clang + lld) or set -DSOCT_BOOT_LLVM_DIR")
        endif ()
    endif ()

    if (SOCT_BOOT_MAKE)
        # Explicit override: still verify it, loudly.
        set(_boot_make "${SOCT_BOOT_MAKE}")
        execute_process(COMMAND ${_boot_make} --version
                OUTPUT_VARIABLE _make_ver ERROR_QUIET OUTPUT_STRIP_TRAILING_WHITESPACE)
        if (_make_ver MATCHES "GNU Make ([0-9]+)\\.([0-9]+)" AND NOT CMAKE_MATCH_1 LESS 4)
            set(_make_ok TRUE)
        else ()
            set(_make_ok FALSE)
            message(STATUS "boot: SOCT_BOOT_MAKE='${_boot_make}' is not GNU make >= 4.0 (kernel requirement) - linux-image will refuse")
        endif ()
    else ()
        # Auto-detect: gmake/gnumake/make from PATH plus Homebrew/MacPorts locations,
        # each candidate version-checked (see shared/cmake/FindGNUMake.cmake).
        find_package(GNUMake 4.0 QUIET)
        if (GNUMake_FOUND)
            set(_boot_make "${GNUMAKE_EXECUTABLE}")
            set(_make_ok TRUE)
            message(STATUS "boot: using GNU make ${GNUMAKE_VERSION} at ${GNUMAKE_EXECUTABLE}")
        else ()
            set(_boot_make "make") # good enough for OpenSBI/musl; the kernel target stays gated
            set(_make_ok FALSE)
            message(STATUS "boot: no GNU make >= 4.0 found (kernel requirement) - linux-image will refuse; install one (macOS: brew install make) or set -DSOCT_BOOT_MAKE=<path>")
        endif ()
    endif ()

    # darwin-compat.h is force-included on top: O_LARGEFILE and copy_file_range for
    # usr/gen_init_cpio.c. And the kernel's generator scripts assume GNU sed (BSD sed
    # renders a replacement \t as a literal 't', which mangles every macro name in the
    # vDSO offsets header into e.g. __vdso_rt_sigreturn_offsett0x800) - so on macOS a
    # PATH shim mapping sed -> gsed is prepended for the kernel invocations only.
    # NB: this project cross-compiles (CMAKE_SYSTEM_NAME=Generic), so the check must be
    # CMAKE_HOST_APPLE - plain APPLE describes the target and is always false here.
    set(_khostflags "")
    set(_kenv "")
    set(_darwin_sed_ok TRUE)
    if (CMAKE_HOST_APPLE)
        set(_khostflags "HOSTCFLAGS=-I${SOCETEER_ROOT}/binaries/linux/host-compat -include ${SOCETEER_ROOT}/binaries/linux/host-compat/darwin-compat.h -D_UUID_T -D__GETHOSTUUID_H")
        find_program(_SOCT_BOOT_GSED NAMES gsed PATHS /opt/homebrew/bin /usr/local/bin /opt/local/bin)
        mark_as_advanced(_SOCT_BOOT_GSED)
        if (_SOCT_BOOT_GSED)
            set(_hostbin "${CMAKE_CURRENT_BINARY_DIR}/hostbin")
            file(MAKE_DIRECTORY "${_hostbin}")
            file(CREATE_LINK "${_SOCT_BOOT_GSED}" "${_hostbin}/sed" SYMBOLIC)
            set(_kenv ${CMAKE_COMMAND} -E env "PATH=${_hostbin}:$ENV{PATH}")
        else ()
            set(_darwin_sed_ok FALSE)
            message(STATUS "boot: no GNU sed found (kernel generator scripts need it on macOS) - linux-image will refuse; brew install gnu-sed")
        endif ()
    endif ()
endmacro()
