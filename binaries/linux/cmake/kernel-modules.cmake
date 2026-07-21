# Out-of-tree kernel modules (drivers/<name>/ with a Kbuild file, auto-discovered): built
# with kbuild against the shared kernel build dir into modules/<name>/ (kbuild writes its
# objects next to the sources it is handed, so they are copied into the build tree first).
# The kernel Image is built beforehand in the same target - it produces the Module.symvers
# that modpost needs - and everything funnels through the soct_kbuild pool. Always-run for
# the same reason as the boot images: kbuild is the dependency tracker. Boot images pack
# the .ko files into their initramfs (see userspace/shell); the kernel tracks the packed
# files, so a rebuilt module re-packs automatically.
#
# Consumes: SOCT_BOOT_LINUX_DIR, _kbuild/_kllvm (boot-image.cmake), _kenv/_khostflags,
#           _boot_make, _ncpu.
# Defines: a <name>-driver target per module, and - once the module has been built and
#          the project reconfigured - a <name>-index target for IDE language support.

file(GLOB _driver_dirs RELATIVE "${CMAKE_CURRENT_SOURCE_DIR}/drivers" "${CMAKE_CURRENT_SOURCE_DIR}/drivers/*")
foreach (_drv ${_driver_dirs})
    if (NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/drivers/${_drv}/Kbuild")
        continue ()
    endif ()
    set(_drv_bld "${CMAKE_CURRENT_BINARY_DIR}/modules/${_drv}")
    add_custom_target(${_drv}-driver
            COMMAND ${CMAKE_COMMAND} -E copy_directory
            "${CMAKE_CURRENT_SOURCE_DIR}/drivers/${_drv}" "${_drv_bld}"
            # `modules` (not just Image) because it emits the kernel's Module.symvers,
            # without which the external modpost resolves no kernel symbol at all.
            COMMAND ${_kenv} ${_boot_make} -C "${SOCT_BOOT_LINUX_DIR}" ARCH=riscv
            ${_kllvm} O=${_kbuild} ${_khostflags} -j${_ncpu} Image modules
            COMMAND ${_kenv} ${_boot_make} -C "${SOCT_BOOT_LINUX_DIR}" ARCH=riscv
            ${_kllvm} O=${_kbuild} ${_khostflags} M=${_drv_bld} modules
            JOB_POOL soct_kbuild
            COMMENT "Building kernel module ${_drv}"
            VERBATIM)
    add_dependencies(${_drv}-driver kernel-config)
    message(STATUS "Adding kernel module ${_drv} (${_drv}-driver)")

    # IDE language support: an index-only OBJECT target over the ORIGINAL sources,
    # carrying the EXACT flags kbuild compiled with - harvested from the .cmd file it
    # writes next to each object (any one will do, flags are per-directory). CLion
    # resolves the target through the CMake model and the editor lights up, headers
    # included (the -I paths are absolute into the kernel trees). EXCLUDE_FROM_ALL keeps
    # it out of every build - the .ko only ever comes from kbuild above - and appended
    # flags win over the project's userspace defaults (clang: last flag wins).
    file(GLOB _drv_cmds "${_drv_bld}/.*.o.cmd")
    if (_drv_cmds)
        list(GET _drv_cmds 0 _drv_cmd)
        file(READ "${_drv_cmd}" _drv_cmdline)
        string(REGEX REPLACE "^[^=]*:= *" "" _drv_cmdline "${_drv_cmdline}")
        string(REGEX REPLACE "\n.*" "" _drv_cmdline "${_drv_cmdline}")
        separate_arguments(_drv_tokens UNIX_COMMAND "${_drv_cmdline}")
        list(POP_FRONT _drv_tokens) # the compiler itself
        set(_drv_flags "")
        set(_drv_skip_next FALSE)
        set(_drv_pair "")
        foreach (_tok IN LISTS _drv_tokens)
            if (_drv_skip_next)
                set(_drv_skip_next FALSE)
                continue ()
            endif ()
            # kbuild runs with the kernel build dir as cwd; make its relative paths
            # absolute so the IDE can resolve them from anywhere.
            string(REGEX REPLACE "^\\./" "${_kbuild}/" _tok "${_tok}")
            string(REGEX REPLACE "^-I\\./" "-I${_kbuild}/" _tok "${_tok}")
            if (_drv_pair)
                # Second half of a flag+argument pair: emit as ONE SHELL: item, because
                # CMake de-duplicates identical COMPILE_OPTIONS tokens - the repeated
                # `-include` words would collapse, orphaning their header paths as
                # extra compiler inputs.
                list(APPEND _drv_flags "SHELL:${_drv_pair} ${_tok}")
                set(_drv_pair "")
                continue ()
            endif ()
            if (_tok STREQUAL "-include" OR _tok STREQUAL "-imacros")
                set(_drv_pair "${_tok}")
                continue ()
            endif ()
            # Drop the per-invocation bookkeeping: dep-file generation, compile/output
            # directives and the source/object paths themselves.
            if (_tok STREQUAL "-o")
                set(_drv_skip_next TRUE)
                continue ()
            endif ()
            if (_tok STREQUAL "-c" OR _tok MATCHES "^-Wp," OR _tok MATCHES "\\.[co]$")
                continue ()
            endif ()
            list(APPEND _drv_flags "${_tok}")
        endforeach ()
        file(GLOB _drv_srcs "${CMAKE_CURRENT_SOURCE_DIR}/drivers/${_drv}/*.c")
        add_library(${_drv}-index OBJECT EXCLUDE_FROM_ALL ${_drv_srcs})
        target_compile_options(${_drv}-index PRIVATE ${_drv_flags})
    else ()
        message(STATUS "linux: driver ${_drv} not indexed yet - build ${_drv}-driver once and reconfigure to get IDE language support. After that, sync CMake changes")
    endif ()
endforeach ()
