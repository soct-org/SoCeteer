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

    # 1. Platform-Agnostic Flex and Bison Discovery
    if (WIN32)
        # Search for win_flex and win_bison executables automatically
        find_program(WIN_FLEX_EXE NAMES win_flex flex)
        find_program(WIN_BISON_EXE NAMES win_bison bison)

        if (WIN_FLEX_EXE AND WIN_BISON_EXE)
            # Extract the directory containing the tools to match your variable intent
            get_filename_component(WIN_FLEX_BISON "${WIN_FLEX_EXE}" DIRECTORY)
            message(STATUS "Found Win Flex Bison path: ${WIN_FLEX_BISON}")
        else()
            message(FATAL_ERROR
                "Win Flex Bison tools not found. "
                "Please install them via Chocolatey/vcpkg or set WIN_FLEX_BISON manually.")
        endif()

        list(APPEND _cfg_cmd "-DWIN_FLEX_BISON=${WIN_FLEX_BISON}")

        # 2. Dynamic Visual Studio Generator Selection
        if (NOT DEFINED CMAKE_GENERATOR)
            if (CMAKE_VERSION VERSION_GREATER_EQUAL "3.31" AND MSVC_VERSION VERSION_GREATER_EQUAL 1940)
                set(_generator "Visual Studio 18 2026")
            else()
                set(_generator "Visual Studio 17 2022")
            endif()
        else()
            set(_generator "${CMAKE_GENERATOR}")
        endif()

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