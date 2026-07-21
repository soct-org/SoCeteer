# The sysroot: kernel UAPI headers + static musl at the toolchain's CMAKE_SYSROOT
# (shared/vendor/riscv64-linux-musl-sysroot). musl is a hash-pinned self-fetch; the
# sysroot is built at configure time when absent, so the IDE indexes against real headers
# from the first configure; the linux-sysroot target rebuilds it on demand.
#
# Consumes: SOCT_BOOT_LINUX_DIR, SOCT_LINUX_MARCH, CMAKE_SYSROOT, SOCETEER_ROOT,
#           _llvm_bindir/_llvm_lld, _boot_make, _kenv (host-tool discovery), _ncpu.
# Defines: the linux-sysroot target.

set(_musl_version "1.2.5")
set(_musl_sha256 "a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4")
set(_musl_tarball "${SOCETEER_ROOT}/shared/vendor/musl/musl-${_musl_version}.tar.gz")
if (NOT EXISTS "${_musl_tarball}")
    message(STATUS "linux: fetching musl ${_musl_version} (musl.libc.org, ~1 MB)")
    file(DOWNLOAD "https://musl.libc.org/releases/musl-${_musl_version}.tar.gz" "${_musl_tarball}"
            EXPECTED_HASH SHA256=${_musl_sha256} STATUS _musl_dl)
    list(GET _musl_dl 0 _musl_dl_code)
    if (NOT _musl_dl_code EQUAL 0)
        file(REMOVE "${_musl_tarball}")
        message(FATAL_ERROR "linux: musl download failed (${_musl_dl})")
    endif ()
endif ()
set(_musl_src "${CMAKE_CURRENT_BINARY_DIR}/musl-${_musl_version}")
if (NOT EXISTS "${_musl_src}/configure")
    file(ARCHIVE_EXTRACT INPUT "${_musl_tarball}" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}")
endif ()

# The rv64/lp64d libgcc of the bare-metal toolchain: its OS-agnostic builtins (soft
# fp128 etc.) stand in for the compiler-rt that prebuilt LLVM does not ship for cross
# targets. Must resolve to a real lp64d multilib, not the toolchain's default.
set(_xpack_gcc "${SOCETEER_ROOT}/shared/vendor/riscv-none-elf-gcc/bin/riscv-none-elf-gcc")
execute_process(COMMAND ${_xpack_gcc} -march=${SOCT_LINUX_MARCH} -mabi=lp64d -print-libgcc-file-name
        OUTPUT_VARIABLE _libgcc OUTPUT_STRIP_TRAILING_WHITESPACE)
if (NOT EXISTS "${_libgcc}" OR NOT _libgcc MATCHES "lp64d")
    message(FATAL_ERROR "linux: the vendored toolchain has no rv64/lp64d libgcc multilib (got '${_libgcc}')")
endif ()

set(_sysroot "${CMAKE_SYSROOT}")
set(_sysroot_stamp "${_sysroot}/.stamp")
if (CMAKE_HOST_APPLE AND _kenv)
    set(_hostbin_line "PATH=\"${CMAKE_CURRENT_BINARY_DIR}/hostbin:$ENV{PATH}\"; export PATH")
else ()
    set(_hostbin_line "")
endif ()
set(HOSTBIN_LINE "${_hostbin_line}")
set(BOOT_MAKE "${_boot_make}")
set(LINUX_DIR "${SOCT_BOOT_LINUX_DIR}")
set(HDR_BUILD "${CMAKE_CURRENT_BINARY_DIR}/sysroot-build/linux-headers")
set(SYSROOT "${_sysroot}")
set(MUSL_BUILD "${CMAKE_CURRENT_BINARY_DIR}/sysroot-build/musl")
set(MUSL_SRC "${_musl_src}")
set(CLANG "${_llvm_bindir}/clang")
set(LLD "${_llvm_lld}")
set(LLVM_AR "${_llvm_bindir}/llvm-ar")
set(LLVM_RANLIB "${_llvm_bindir}/llvm-ranlib")
set(MARCH "${SOCT_LINUX_MARCH}")
set(LIBGCC "${_libgcc}")
set(NCPU "${_ncpu}")
set(STAMP "${_sysroot_stamp}")
configure_file(build-sysroot.sh.in "${CMAKE_CURRENT_BINARY_DIR}/build-sysroot.sh" @ONLY)

if (NOT EXISTS "${_sysroot_stamp}")
    message(STATUS "linux: sysroot missing - building it now (kernel headers + musl, ~2 min)")
    execute_process(COMMAND sh "${CMAKE_CURRENT_BINARY_DIR}/build-sysroot.sh"
            RESULT_VARIABLE _sysroot_res)
    if (NOT _sysroot_res EQUAL 0)
        message(FATAL_ERROR "linux: sysroot build failed (see output above)")
    endif ()
endif ()
add_custom_command(OUTPUT "${_sysroot_stamp}"
        COMMAND sh "${CMAKE_CURRENT_BINARY_DIR}/build-sysroot.sh"
        DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/build-sysroot.sh" "${_musl_src}/configure"
        COMMENT "Building riscv64-linux-musl sysroot (kernel headers + musl)"
        VERBATIM)
add_custom_target(linux-sysroot DEPENDS "${_sysroot_stamp}")
