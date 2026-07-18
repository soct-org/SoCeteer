# The riscv64-linux-musl sysroot: the kernel tree's UAPI headers plus a static musl,
# for compiling Linux userspace programs (see programs/linux-userspace.cmake). Skipped
# with a status message when binaries/linux-stable/ is absent.
#
# musl is compiled with the same LLVM the kernel/OpenSBI builds use (discovered by
# shared/cmake/SoctBootHostTools.cmake); the repo's bare-metal toolchain contributes
# exactly one artifact: its rv64/lp64d libgcc.a, whose OS-agnostic builtins (soft
# fp128 arithmetic and friends) stand in for the compiler-rt that prebuilt LLVM does
# not ship for cross targets. The musl source tarball is fetched once (hash-pinned)
# into shared/vendor/musl/ and cached there across build directories.
#
# Exposed to the rest of the build (cache-internal, refreshed every configure):
#   SOCT_LINUX_USERSPACE_OK      the template may create targets
#   SOCT_LINUX_SYSROOT           the sysroot directory
#   SOCT_LINUX_SYSROOT_STAMP     file-level dependency for consumers
#   SOCT_LINUX_CLANG / SOCT_LINUX_LLD / SOCT_LINUX_MARCH   compile/link ingredients

include(ProcessorCount)
include(SoctBootHostTools)

set(SOCT_BOOT_LINUX_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../linux-stable" CACHE PATH
        "Linux kernel source tree")

set(_musl_version "1.2.5")
set(_musl_sha256 "a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4")

function(soct_linux_userspace_unavailable why)
    message(STATUS "linux-sysroot: ${why} - Linux userspace programs will be skipped")
    set(SOCT_LINUX_USERSPACE_OK FALSE CACHE INTERNAL "" FORCE)
endfunction()

if (NOT EXISTS "${SOCT_BOOT_LINUX_DIR}/Makefile")
    soct_linux_userspace_unavailable("no kernel tree at ${SOCT_BOOT_LINUX_DIR}")
    return ()
endif ()

soct_boot_find_host_tools()
if (NOT _path_ok OR NOT _llvm_ok OR NOT _make_ok)
    soct_linux_userspace_unavailable("host requirements unmet (see the boot: messages above)")
    soct_boot_blocked(linux-sysroot
            "host requirements for the sysroot build are unmet (see the boot: messages at configure time)"
            "LLVM with RISC-V + ld.lld, GNU make >= 4.0, and a comma/space-free build path are required")
    return ()
endif ()

# --- musl source: fetched once, hash-pinned, cached in shared/vendor -----------------
set(_musl_tarball "${SOCETEER_ROOT}/shared/vendor/musl/musl-${_musl_version}.tar.gz")
if (NOT EXISTS "${_musl_tarball}")
    message(STATUS "linux-sysroot: fetching musl ${_musl_version} (musl.libc.org, ~1 MB)")
    file(DOWNLOAD "https://musl.libc.org/releases/musl-${_musl_version}.tar.gz" "${_musl_tarball}"
            EXPECTED_HASH SHA256=${_musl_sha256} STATUS _musl_dl)
    list(GET _musl_dl 0 _musl_dl_code)
    if (NOT _musl_dl_code EQUAL 0)
        file(REMOVE "${_musl_tarball}")
        soct_linux_userspace_unavailable("musl download failed (${_musl_dl})")
        return ()
    endif ()
endif ()
set(_musl_src "${CMAKE_CURRENT_BINARY_DIR}/musl-${_musl_version}")
if (NOT EXISTS "${_musl_src}/configure")
    file(ARCHIVE_EXTRACT INPUT "${_musl_tarball}" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}")
endif ()

# --- The libgcc that provides compiler runtime helpers -------------------------------
# Standard riscv64 Linux userspace ABI: lp64d (hard float), independent of the SoC's
# bare-metal SOCT_ABI. The march must resolve to a real lp64d multilib, not the
# toolchain's default - verified below.
set(_linux_march "rv64imafdc_zicsr_zifencei")
execute_process(COMMAND ${CMAKE_C_COMPILER} -march=${_linux_march} -mabi=lp64d -print-libgcc-file-name
        OUTPUT_VARIABLE _libgcc OUTPUT_STRIP_TRAILING_WHITESPACE)
if (NOT EXISTS "${_libgcc}" OR NOT _libgcc MATCHES "lp64d")
    soct_linux_userspace_unavailable("the vendored toolchain has no rv64/lp64d libgcc multilib (got '${_libgcc}')")
    return ()
endif ()

# --- The sysroot build ---------------------------------------------------------------
ProcessorCount(_ncpu)
if (_ncpu EQUAL 0)
    set(_ncpu 4)
endif ()
set(_sysroot "${CMAKE_CURRENT_BINARY_DIR}/sysroot")
set(_stamp "${CMAKE_CURRENT_BINARY_DIR}/sysroot.stamp")

if (CMAKE_HOST_APPLE AND _kenv)
    set(_hostbin_line "PATH=\"${CMAKE_CURRENT_BINARY_DIR}/hostbin:$ENV{PATH}\"; export PATH")
else ()
    set(_hostbin_line "")
endif ()
set(HOSTBIN_LINE "${_hostbin_line}")
set(BOOT_MAKE "${_boot_make}")
set(LINUX_DIR "${SOCT_BOOT_LINUX_DIR}")
set(HDR_BUILD "${CMAKE_CURRENT_BINARY_DIR}/linux-headers")
set(SYSROOT "${_sysroot}")
set(MUSL_BUILD "${CMAKE_CURRENT_BINARY_DIR}/musl-build")
set(MUSL_SRC "${_musl_src}")
set(CLANG "${_llvm_bindir}/clang")
set(LLD "${_llvm_lld}")
set(LLVM_AR "${_llvm_bindir}/llvm-ar")
set(LLVM_RANLIB "${_llvm_bindir}/llvm-ranlib")
set(MARCH "${_linux_march}")
set(LIBGCC "${_libgcc}")
set(NCPU "${_ncpu}")
set(STAMP "${_stamp}")
configure_file(build-sysroot.sh.in "${CMAKE_CURRENT_BINARY_DIR}/build-sysroot.sh" @ONLY)

add_custom_command(OUTPUT "${_stamp}"
        COMMAND sh "${CMAKE_CURRENT_BINARY_DIR}/build-sysroot.sh"
        DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/build-sysroot.sh" "${_musl_src}/configure"
        COMMENT "Building riscv64-linux-musl sysroot (kernel headers + musl)"
        VERBATIM)
add_custom_target(linux-sysroot DEPENDS "${_stamp}")

set(SOCT_LINUX_USERSPACE_OK TRUE CACHE INTERNAL "" FORCE)
set(SOCT_LINUX_SYSROOT "${_sysroot}" CACHE INTERNAL "" FORCE)
set(SOCT_LINUX_SYSROOT_STAMP "${_stamp}" CACHE INTERNAL "" FORCE)
set(SOCT_LINUX_CLANG "${_llvm_bindir}/clang" CACHE INTERNAL "" FORCE)
set(SOCT_LINUX_LLD "${_llvm_lld}" CACHE INTERNAL "" FORCE)
set(SOCT_LINUX_MARCH "${_linux_march}" CACHE INTERNAL "" FORCE)
