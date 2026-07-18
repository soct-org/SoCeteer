# FindLLVMRiscv
# -------------
# Finds an LLVM toolchain (clang + ld.lld + llvm-* binutils) able to target RISC-V:
#
#   find_package(LLVMRiscv)
#
# Result variables:
#   LLVMRiscv_FOUND      - a capable toolchain exists
#   LLVMRISCV_BIN_DIR    - one directory containing every tool (pass as LLVM=<dir>/)
#   LLVMRISCV_LLD        - absolute path to ld.lld (for -fuse-ld=<path> consumers)
#   LLVMRISCV_VERSION    - the clang version
#
# Two realities this module absorbs:
#  - Mainline LLVM carries every backend, but the clang actually installed may not:
#    Apple's Xcode clang strips the RISC-V target and ships no ld.lld. Every candidate
#    is therefore proved by linking a RISC-V PIE through ld.lld - the capability the
#    kernel's vDSO (-shared) and OpenSBI (-pie) actually need - never trusted by name.
#  - The tools are not always in one directory: Homebrew splits llvm and lld into
#    separate keg-only formulas. Kbuild's LLVM=<dir>/ mode wants one directory, so
#    when the pieces live apart, a directory of symlinks is assembled in the build
#    tree and returned instead.

# --- Candidate directories for clang -------------------------------------------------
set(_llvmriscv_dirs "")
find_program(_LLVMRISCV_PATH_CLANG NAMES clang)
mark_as_advanced(_LLVMRISCV_PATH_CLANG)
if (_LLVMRISCV_PATH_CLANG)
    file(REAL_PATH "${_LLVMRISCV_PATH_CLANG}" _llvmriscv_real)
    cmake_path(GET _llvmriscv_real PARENT_PATH _llvmriscv_dir)
    list(APPEND _llvmriscv_dirs "${_llvmriscv_dir}")
endif ()
# Homebrew (Apple silicon + Intel; opt/ keg aliases exist even when unlinked), newest first
foreach (_prefix /opt/homebrew /usr/local)
    file(GLOB _llvmriscv_kegs "${_prefix}/opt/llvm*/bin")
    list(SORT _llvmriscv_kegs ORDER DESCENDING)
    list(APPEND _llvmriscv_dirs ${_llvmriscv_kegs})
endforeach ()
# Debian/Ubuntu versioned trees, newest first; MacPorts
file(GLOB _llvmriscv_deb "/usr/lib/llvm-*/bin")
list(SORT _llvmriscv_deb COMPARE NATURAL ORDER DESCENDING)
file(GLOB _llvmriscv_macports "/opt/local/libexec/llvm-*/bin")
list(SORT _llvmriscv_macports COMPARE NATURAL ORDER DESCENDING)
list(APPEND _llvmriscv_dirs ${_llvmriscv_deb} ${_llvmriscv_macports})
list(REMOVE_DUPLICATES _llvmriscv_dirs)

# --- Locate ld.lld for a given clang directory ---------------------------------------
# Same directory first; otherwise the split-formula locations and PATH.
function(_llvmriscv_find_lld clang_dir out_var)
    if (EXISTS "${clang_dir}/ld.lld")
        set(${out_var} "${clang_dir}/ld.lld" PARENT_SCOPE)
        return ()
    endif ()
    foreach (_dir /opt/homebrew/opt/lld/bin /usr/local/opt/lld/bin)
        if (EXISTS "${_dir}/ld.lld")
            set(${out_var} "${_dir}/ld.lld" PARENT_SCOPE)
            return ()
        endif ()
    endforeach ()
    find_program(_LLVMRISCV_PATH_LLD NAMES ld.lld)
    mark_as_advanced(_LLVMRISCV_PATH_LLD)
    if (_LLVMRISCV_PATH_LLD)
        set(${out_var} "${_LLVMRISCV_PATH_LLD}" PARENT_SCOPE)
    else ()
        set(${out_var} "" PARENT_SCOPE)
    endif ()
endfunction()

set(LLVMRISCV_BIN_DIR "")
set(LLVMRISCV_LLD "")
set(LLVMRISCV_VERSION "")
set(_llvmriscv_tools clang clang++ llvm-ar llvm-nm llvm-objcopy llvm-objdump llvm-ranlib llvm-readelf llvm-strip)

foreach (_dir IN LISTS _llvmriscv_dirs)
    if (NOT EXISTS "${_dir}/clang" OR NOT EXISTS "${_dir}/llvm-objcopy")
        continue ()
    endif ()
    _llvmriscv_find_lld("${_dir}" _llvmriscv_lld)
    if (NOT _llvmriscv_lld)
        continue ()
    endif ()
    # Prove the pair: link a RISC-V PIE through this exact ld.lld (absolute -fuse-ld,
    # so the driver's own linker search plays no part in the verdict).
    execute_process(
            COMMAND "${_dir}/clang" --target=riscv64-unknown-elf -fPIE -nostdlib
            "--ld-path=${_llvmriscv_lld}" -Wl,-pie -x c /dev/null
            -o "${CMAKE_BINARY_DIR}/llvmriscv-probe"
            RESULT_VARIABLE _llvmriscv_res OUTPUT_QUIET ERROR_QUIET)
    file(REMOVE "${CMAKE_BINARY_DIR}/llvmriscv-probe")
    if (NOT _llvmriscv_res EQUAL 0)
        continue ()
    endif ()

    execute_process(COMMAND "${_dir}/clang" --version
            OUTPUT_VARIABLE _llvmriscv_ver_out ERROR_QUIET OUTPUT_STRIP_TRAILING_WHITESPACE)
    if (_llvmriscv_ver_out MATCHES "clang version ([0-9]+(\\.[0-9]+)*)")
        set(LLVMRISCV_VERSION "${CMAKE_MATCH_1}")
    endif ()

    cmake_path(GET _llvmriscv_lld PARENT_PATH _llvmriscv_lld_dir)
    if (_llvmriscv_lld_dir STREQUAL _dir)
        set(LLVMRISCV_BIN_DIR "${_dir}")
        set(LLVMRISCV_LLD "${_llvmriscv_lld}")
    else ()
        # Pieces live apart (Homebrew): assemble one directory of symlinks so the
        # kernel's LLVM=<dir>/ convention has a single home for every tool.
        set(_llvmriscv_shim "${CMAKE_BINARY_DIR}/llvmriscv-bin")
        file(MAKE_DIRECTORY "${_llvmriscv_shim}")
        foreach (_tool IN LISTS _llvmriscv_tools)
            if (EXISTS "${_dir}/${_tool}")
                file(CREATE_LINK "${_dir}/${_tool}" "${_llvmriscv_shim}/${_tool}" SYMBOLIC)
            endif ()
        endforeach ()
        file(CREATE_LINK "${_llvmriscv_lld}" "${_llvmriscv_shim}/ld.lld" SYMBOLIC)
        set(LLVMRISCV_BIN_DIR "${_llvmriscv_shim}")
        set(LLVMRISCV_LLD "${_llvmriscv_shim}/ld.lld")
    endif ()
    break ()
endforeach ()

include(FindPackageHandleStandardArgs)
find_package_handle_standard_args(LLVMRiscv
        REQUIRED_VARS LLVMRISCV_BIN_DIR LLVMRISCV_LLD
        VERSION_VAR LLVMRISCV_VERSION)
