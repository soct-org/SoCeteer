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
    # On Windows, use the Visual Studio generator so that Verilator is compiled with MSVC.
    # Verilator built with MinGW/GCC crashes (Access Violation) when processing large designs
    # on Windows; the MSVC build is stable. The simulation binary is still compiled with
    # the project's own toolchain (typically MinGW GCC via Ninja) in a separate CMake invocation.
    if (WIN32)
        set(_cfg_cmd ${CMAKE_COMMAND} -S "${VERILATOR_SOURCE}" -B "${VERILATOR_BUILD}"
            -DCMAKE_BUILD_TYPE=Release
            -G "Visual Studio 17 2022" -A x64)
    else()
        set(_cfg_cmd ${CMAKE_COMMAND} -S "${VERILATOR_SOURCE}" -B "${VERILATOR_BUILD}"
            -DCMAKE_BUILD_TYPE=Release -G "Ninja")
    endif()

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
       list(APPEND _cfg_cmd "-DWIN_FLEX_BISON=${WIN_FLEX_BISON}")
    elseif(APPLE)
        find_program(BREW_EXECUTABLE brew)
        if(BREW_EXECUTABLE)
            execute_process(
                COMMAND "${BREW_EXECUTABLE}" --prefix flex
                RESULT_VARIABLE _brew_flex_res
                OUTPUT_VARIABLE _brew_flex_prefix
                ERROR_VARIABLE _brew_flex_err
                OUTPUT_STRIP_TRAILING_WHITESPACE
            )

            execute_process(
                COMMAND "${BREW_EXECUTABLE}" --prefix bison
                RESULT_VARIABLE _brew_bison_res
                OUTPUT_VARIABLE _brew_bison_prefix
                ERROR_VARIABLE _brew_bison_err
                OUTPUT_STRIP_TRAILING_WHITESPACE
            )

            set(_prefix_path "")
            set(_include_path "")
            set(_library_path "")

            if(_brew_flex_res EQUAL 0 AND NOT "${_brew_flex_prefix}" STREQUAL "")
                list(APPEND _prefix_path "${_brew_flex_prefix}")
                list(APPEND _include_path "${_brew_flex_prefix}/include")
                list(APPEND _library_path "${_brew_flex_prefix}/lib")
                list(APPEND _cfg_cmd "-DFLEX_EXECUTABLE=${_brew_flex_prefix}/bin/flex")
                list(APPEND _cfg_cmd "-DFLEX_INCLUDE_DIR=${_brew_flex_prefix}/include")
            else()
                message(WARNING
                    "Brew flex not found, relying on Verilator/CMake to find flex. Error: ${_brew_flex_err}")
            endif()

            if(_brew_bison_res EQUAL 0 AND NOT "${_brew_bison_prefix}" STREQUAL "")
                list(APPEND _prefix_path "${_brew_bison_prefix}")
                list(APPEND _include_path "${_brew_bison_prefix}/include")
                list(APPEND _library_path "${_brew_bison_prefix}/lib")
                list(APPEND _cfg_cmd "-DBISON_EXECUTABLE=${_brew_bison_prefix}/bin/bison")
            else()
                message(WARNING
                    "Brew bison not found, relying on Verilator/CMake to find bison. Error: ${_brew_bison_err}")
            endif()

            if(_prefix_path)
                list(REMOVE_DUPLICATES _prefix_path)
                list(JOIN _prefix_path ";" _prefix_path_str)
                list(APPEND _cfg_cmd "-DCMAKE_PREFIX_PATH=${_prefix_path_str}")
            endif()

            if(_include_path)
                list(REMOVE_DUPLICATES _include_path)
                list(JOIN _include_path ";" _include_path_str)
                list(APPEND _cfg_cmd "-DCMAKE_INCLUDE_PATH=${_include_path_str}")
            endif()

            if(_library_path)
                list(REMOVE_DUPLICATES _library_path)
                list(JOIN _library_path ";" _library_path_str)
                list(APPEND _cfg_cmd "-DCMAKE_LIBRARY_PATH=${_library_path_str}")
            endif()
        else()
            message(WARNING "Homebrew not found, relying on Verilator/CMake to find flex and bison. Please install Homebrew to improve chances of Verilator finding flex and bison on macOS.")
        endif()
    endif()


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