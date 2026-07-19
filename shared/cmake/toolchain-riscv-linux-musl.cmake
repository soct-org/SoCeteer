# CMake toolchain for riscv64 LINUX userspace (static musl) - the toolchain of the
# binaries/linux project. The bare-metal xpack toolchain cannot target Linux (newlib,
# no dynamic linking support in its binutils), so this world compiles with a discovered
# LLVM/Clang against the musl sysroot built by the linux project itself.
#
# The sysroot lives at a build-directory-independent location
# (shared/vendor/riscv64-linux-musl-sysroot) so every IDE profile and build tree shares
# one copy; the linux project builds it on demand. Compiler detection is done with
# static-library try-compiles so configuring works before the sysroot exists.
#
# NOTE: toolchain files are re-run for every try_compile, so everything here must be
# self-contained and cheap - discovery goes through FindLLVMRiscv (find_program caches,
# one small link probe).

set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR riscv64)

cmake_path(GET CMAKE_CURRENT_LIST_DIR PARENT_PATH _soct_shared_dir)
cmake_path(GET _soct_shared_dir PARENT_PATH _soct_root)

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}")
find_package(LLVMRiscv QUIET)
if (NOT LLVMRiscv_FOUND)
    message(FATAL_ERROR "toolchain-riscv-linux-musl: no RISC-V-capable LLVM found (clang + ld.lld). Install one (macOS: brew install llvm lld; Debian: clang + lld) or set -DSOCT_BOOT_LLVM_DIR for the project (see shared/cmake/SoctBootHostTools.cmake).")
endif ()
# Real tool paths (not the assembled shim dir): the compiler must be identical across
# the main configure and every try_compile re-run, which use different binary dirs.
# NB: resolve ld.lld's DIRECTORY only, keeping the flavored basename - a full REAL_PATH
# lands on the generic `lld` driver binary, which refuses to link unless invoked under
# a flavor name (--ld-path passes the path as argv0 verbatim).
file(REAL_PATH "${LLVMRISCV_BIN_DIR}/clang" _soct_linux_clang)
file(REAL_PATH "${LLVMRISCV_LLD}" _soct_linux_lld_real)
cmake_path(GET _soct_linux_lld_real PARENT_PATH _soct_linux_lld_dir)
set(_soct_linux_lld "${_soct_linux_lld_dir}/ld.lld")
if (NOT EXISTS "${_soct_linux_lld}")
    message(FATAL_ERROR "toolchain-riscv-linux-musl: no ld.lld next to ${_soct_linux_lld_real}")
endif ()

set(CMAKE_C_COMPILER "${_soct_linux_clang}")
set(CMAKE_CXX_COMPILER "${_soct_linux_clang}++")
set(CMAKE_C_COMPILER_TARGET riscv64-unknown-linux-musl)
set(CMAKE_CXX_COMPILER_TARGET riscv64-unknown-linux-musl)
set(CMAKE_AR "${LLVMRISCV_BIN_DIR}/llvm-ar" CACHE FILEPATH "")
set(CMAKE_RANLIB "${LLVMRISCV_BIN_DIR}/llvm-ranlib" CACHE FILEPATH "")

set(CMAKE_SYSROOT "${_soct_root}/shared/vendor/riscv64-linux-musl-sysroot")

# Standard riscv64 Linux userspace ABI (lp64d, hard float) - deliberately independent
# of the SoC's bare-metal SOCT_ABI. Must match the sysroot's musl build.
set(SOCT_LINUX_MARCH "rv64imafdc_zicsr_zifencei")
set(CMAKE_C_FLAGS_INIT "-march=${SOCT_LINUX_MARCH} -mabi=lp64d")
set(CMAKE_CXX_FLAGS_INIT "-march=${SOCT_LINUX_MARCH} -mabi=lp64d")
# Static: the target system is initramfs-only, there is no shared-library ecosystem.
# --rtlib=libgcc resolves compiler runtime helpers from the libgcc.a the sysroot build
# copies in (prebuilt LLVM ships no compiler-rt for cross targets).
set(CMAKE_EXE_LINKER_FLAGS_INIT "--ld-path=${_soct_linux_lld} --rtlib=libgcc -static")

# The ABI-detection step links nothing, so a not-yet-built sysroot cannot fail it.
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)

set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
