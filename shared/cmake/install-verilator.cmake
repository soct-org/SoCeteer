cmake_minimum_required(VERSION 3.20)


function(_run_logged_process _step_name)
    set(options)
    set(oneValueArgs RESULT_VAR OUT_VAR ERR_VAR)
    set(multiValueArgs COMMAND)
    cmake_parse_arguments(RLP "${options}" "${oneValueArgs}" "${multiValueArgs}" ${ARGN})

    execute_process(
        COMMAND ${RLP_COMMAND}
        RESULT_VARIABLE _res
        OUTPUT_VARIABLE _out
        ERROR_VARIABLE _err
        ECHO_OUTPUT_VARIABLE
        ECHO_ERROR_VARIABLE
        COMMAND_ECHO STDOUT
    )

    set(${RLP_RESULT_VAR} "${_res}" PARENT_SCOPE)
    set(${RLP_OUT_VAR} "${_out}" PARENT_SCOPE)
    set(${RLP_ERR_VAR} "${_err}" PARENT_SCOPE)

    if(NOT _res EQUAL 0)
        message(FATAL_ERROR
            "${_step_name} failed with exit code ${_res}\n"
            "stdout:\n${_out}\n"
            "stderr:\n${_err}"
        )
    endif()
endfunction()



###################################################
# Function to install Verilator
# Requires:
# VERILATOR_SOURCE - path to Verilator source
# (i.e. the path to the Verilator repo)
# VERILATOR_INSTALL - path to install Verilator to
# Outputs:
# VERILATOR_ROOT - path to installed Verilator
# VERILATOR_EXE - path to Verilator executable
###################################################
function(install_verilator)
    message(STATUS "Using Verilator source from ${VERILATOR_SOURCE}")
    message(STATUS "Installing Verilator in ${VERILATOR_INSTALL}")
    set(VERILATOR_BUILD "${VERILATOR_SOURCE}/build")

    # Determine number of processors for parallel build. Default to 2 if cannot be determined.
    include(ProcessorCount)
    ProcessorCount(N)
    if(NOT N EQUAL 0)
        message(STATUS "Configuring Verilator build to use ${N} parallel jobs")
    else()
        set(N 2)
        message(WARNING "Could not determine number of processors, defaulting to ${N} parallel jobs for Verilator build")
    endif()

    execute_process(COMMAND ${CMAKE_COMMAND} -E make_directory ${VERILATOR_BUILD})

    # configure step
    set(_cfg_cmd ${CMAKE_COMMAND} -S "${VERILATOR_SOURCE}" -B "${VERILATOR_BUILD}" -DCMAKE_BUILD_TYPE=Release)
    set(_generator "Ninja")

    if (WIN32)
       if (NOT DEFINED WIN_FLEX_BISON AND DEFINED ENV{WIN_FLEX_BISON})
           set(WIN_FLEX_BISON "$ENV{WIN_FLEX_BISON}")
       elseif (NOT DEFINED WIN_FLEX_BISON)
           if (DEFINED ENV{ChocolateyInstall})
               file(TO_CMAKE_PATH "$ENV{ChocolateyInstall}/lib/winflexbison3/tools" WIN_FLEX_BISON)
           elseif (DEFINED ENV{ProgramData})
               file(TO_CMAKE_PATH "$ENV{ProgramData}/chocolatey/lib/winflexbison3/tools" WIN_FLEX_BISON)
           elseif (DEFINED ENV{ALLUSERSPROFILE})
               file(TO_CMAKE_PATH "$ENV{ALLUSERSPROFILE}/chocolatey/lib/winflexbison3/tools" WIN_FLEX_BISON)
           else()
               message(FATAL_ERROR
                   "Could not determine Chocolatey install root. "
                   "Please set WIN_FLEX_BISON to the Win Flex Bison tools directory.")
           endif()

           if (EXISTS "${WIN_FLEX_BISON}")
               message(STATUS "Using Win Flex Bison path: ${WIN_FLEX_BISON}")
           else()
               message(FATAL_ERROR
                   "WIN_FLEX_BISON variable not defined and derived path "
                   "${WIN_FLEX_BISON} does not exist. Please set WIN_FLEX_BISON "
                   "to the path where Win Flex Bison is installed.")
           endif()
       endif()
       # Select the generator for the Verilator HOST-TOOL build independently of the
       # outer project: verilator_bin is only a code generator, so its compiler does
       # not need to match the simulation toolchain. MinGW-built Verilator is fragile
       # across runner-image toolchain bumps (GCC 15.2 from the June 2026 windows-latest
       # image produces a verilator_bin.exe that crashes with 'Access violation'), so
       # build it with MSVC - Verilator's supported Windows compiler.
       # NOTE: CMAKE_GENERATOR is ALWAYS defined in project mode (it is the OUTER
       # project's generator, e.g. Ninja), so it must never gate this choice - that
       # was the bug that silently kept Verilator on MinGW.
       if (DEFINED ENV{SOCT_VERILATOR_GENERATOR})
           set(_generator "$ENV{SOCT_VERILATOR_GENERATOR}")
           message(STATUS "Verilator generator overridden via SOCT_VERILATOR_GENERATOR: ${_generator}")
       else()
           # Locate the Visual Studio C++ toolset via vswhere (installed with any VS or
           # Build Tools). Use installationVersion (plain "major.minor..."), NOT
           # catalog_productLineVersion: the latter returns "2022" for VS 2022 but "18"
           # for VS 2026 - Microsoft changed the naming scheme.
           set(_vswhere "$ENV{ProgramFiles\(x86\)}/Microsoft Visual Studio/Installer/vswhere.exe")
           set(_vs_version "")
           set(_vs_major "")
           if (EXISTS "${_vswhere}")
               execute_process(
                   COMMAND "${_vswhere}" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationVersion
                   OUTPUT_VARIABLE _vs_version
                   OUTPUT_STRIP_TRAILING_WHITESPACE
                   ERROR_QUIET
               )
               string(REGEX MATCH "^[0-9]+" _vs_major "${_vs_version}")
           endif()
           if (_vs_major STREQUAL "18")
               if (CMAKE_VERSION VERSION_LESS "3.31")
                   message(FATAL_ERROR
                       "Visual Studio 2026 (v${_vs_version}) found, but CMake ${CMAKE_VERSION} is too old "
                       "for the 'Visual Studio 18 2026' generator (needs >= 3.31). Update CMake.")
               endif()
               set(_generator "Visual Studio 18 2026")
           elseif (_vs_major STREQUAL "17")
               set(_generator "Visual Studio 17 2022")
           elseif (_vs_major STREQUAL "16")
               set(_generator "Visual Studio 16 2019")
           else()
               message(FATAL_ERROR
                   "No usable Visual Studio C++ toolset found (vswhere installationVersion: '${_vs_version}'). "
                   "Verilator is built with MSVC on Windows because MinGW-built binaries are "
                   "known to crash at runtime (e.g. MinGW GCC 15.2 -> 'Access violation'). "
                   "Install the Visual Studio Build Tools C++ workload, or set the environment "
                   "variable SOCT_VERILATOR_GENERATOR=Ninja to explicitly accept building "
                   "Verilator with the current MinGW toolchain.")
           endif()
           list(APPEND _cfg_cmd -A x64)
       endif()
       list(APPEND _cfg_cmd "-DWIN_FLEX_BISON=${WIN_FLEX_BISON}")
    elseif(APPLE)
        list(APPEND _cfg_cmd "-DFLEX_INCLUDE_DIR=\"\"")
    endif()

    # Add generator
    list(APPEND _cfg_cmd -G "${_generator}")

    _run_logged_process(
        "CMake configure"
        RESULT_VAR _cfg_res
        OUT_VAR _cfg_out
        ERR_VAR _cfg_err
        COMMAND ${_cfg_cmd}
    )

    set(_build_cmd ${CMAKE_COMMAND} --build "${VERILATOR_BUILD}" --parallel ${N} --config Release)
    _run_logged_process(
        "CMake build"
        RESULT_VAR _build_res
        OUT_VAR _build_out
        ERR_VAR _build_err
        COMMAND ${_build_cmd}
    )

    set(_inst_cmd ${CMAKE_COMMAND} --install "${VERILATOR_BUILD}" --prefix "${VERILATOR_INSTALL}" --config Release)
    _run_logged_process(
        "CMake install"
        RESULT_VAR _inst_res
        OUT_VAR _inst_out
        ERR_VAR _inst_err
        COMMAND ${_inst_cmd}
    )

    # store executable path in a local variable, then export to parent scope
    if (WIN32)
        set(_verilator_exec "${VERILATOR_INSTALL}/bin/verilator_bin.exe")
    else ()
        set(_verilator_exec "${VERILATOR_INSTALL}/bin/verilator_bin")
    endif()

    set(VERILATOR_ROOT "${VERILATOR_INSTALL}" PARENT_SCOPE)
    set(VERILATOR_EXE "${_verilator_exec}" PARENT_SCOPE)
endfunction()

# If run in Script mode, run installation
if (CMAKE_SCRIPT_MODE_FILE)
    if (NOT DEFINED VERILATOR_SOURCE)
        message(FATAL_ERROR "VERILATOR_SOURCE not defined. Please provide path to Verilator source.")
    endif()
    if (NOT DEFINED VERILATOR_INSTALL)
        message(FATAL_ERROR "VERILATOR_INSTALL not defined. Please provide path to install Verilator to.")
    endif()
    install_verilator()
    message(STATUS "Verilator installed to ${VERILATOR_ROOT}")
    message(STATUS "Verilator executable at ${VERILATOR_EXE}")
endif()