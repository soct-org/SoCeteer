# Kernel patches (patches/*.patch): minimal upstream-candidate fixes the boot depends on -
# see patches/README.md for the policy and each patch header for its rationale. Applied to
# the kernel tree once, idempotently: an already-applied patch is detected and skipped, a
# conflicting tree fails the configure loudly. `git apply` on purpose: it never prompts -
# macOS's BSD `patch` 2.0 answers its own "previously applied?" prompt with yes when
# stdin is silent, reporting unapplied patches as applied.
#
# Consumes: SOCT_BOOT_LINUX_DIR. Defines: nothing (mutates the kernel working tree).

file(GLOB _kernel_patches "${CMAKE_CURRENT_SOURCE_DIR}/patches/*.patch")
list(SORT _kernel_patches)
if (_kernel_patches)
    execute_process(COMMAND git -C "${SOCT_BOOT_LINUX_DIR}" rev-parse --is-inside-work-tree
            RESULT_VARIABLE _ktree_git OUTPUT_QUIET ERROR_QUIET)
    if (NOT _ktree_git EQUAL 0)
        message(FATAL_ERROR "linux: ${SOCT_BOOT_LINUX_DIR} is not a git tree, so the kernel patches in patches/ cannot be applied reliably - use a git checkout (the standard submodule), or apply them manually and make the tree a git repository.")
    endif ()
endif ()
foreach (_kpatch ${_kernel_patches})
    execute_process(COMMAND git -C "${SOCT_BOOT_LINUX_DIR}" apply --reverse --check "${_kpatch}"
            RESULT_VARIABLE _kpatch_applied OUTPUT_QUIET ERROR_QUIET)
    if (_kpatch_applied EQUAL 0)
        continue () # already applied
    endif ()
    execute_process(COMMAND git -C "${SOCT_BOOT_LINUX_DIR}" apply --check "${_kpatch}"
            RESULT_VARIABLE _kpatch_ok ERROR_VARIABLE _kpatch_err)
    if (NOT _kpatch_ok EQUAL 0)
        message(FATAL_ERROR "linux: kernel patch ${_kpatch} is neither applied nor applicable to ${SOCT_BOOT_LINUX_DIR}:\n${_kpatch_err}\nThe tree likely diverged from the version the patch was written against - rebase the patch or restore the tree.")
    endif ()
    execute_process(COMMAND git -C "${SOCT_BOOT_LINUX_DIR}" apply "${_kpatch}"
            RESULT_VARIABLE _kpatch_res)
    if (NOT _kpatch_res EQUAL 0)
        message(FATAL_ERROR "linux: failed to apply kernel patch ${_kpatch} (the --check passed - tree changed mid-configure?)")
    endif ()
    message(STATUS "linux: applied kernel patch ${_kpatch}")
endforeach ()
