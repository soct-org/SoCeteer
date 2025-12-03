cmake_minimum_required(VERSION 3.20)

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
        math(EXPR N "${N} - 1") # Use one less than available processors - freezes system otherwise
    else()
        set(N 2)
    endif()

    execute_process(COMMAND ${CMAKE_COMMAND} -E make_directory ${VERILATOR_BUILD})

    # configure step
    set(_cfg_cmd ${CMAKE_COMMAND} -S "${VERILATOR_SOURCE}" -B "${VERILATOR_BUILD}" -DCMAKE_BUILD_TYPE=Release)

    if (WIN32)
        if (NOT DEFINED WIN_FLEX_BISON AND DEFINED ENV{WIN_FLEX_BISON})
            set(WIN_FLEX_BISON $ENV{WIN_FLEX_BISON})
        elseif (NOT DEFINED WIN_FLEX_BISON)
            set(WIN_FLEX_BISON "C:\\ProgramData\\chocolatey\\lib\\winflexbison3\\tools")
        endif()
        list(APPEND _cfg_cmd -DWIN_FLEX_BISON=${WIN_FLEX_BISON})
    endif()

    execute_process(
            COMMAND ${_cfg_cmd}
            RESULT_VARIABLE _cfg_res
            OUTPUT_VARIABLE _cfg_out
            ERROR_VARIABLE _cfg_err
    )
    if(NOT _cfg_res EQUAL 0)
        message(FATAL_ERROR "CMake configure failed:\n${_cfg_err}")
    endif()

    # build step
    execute_process(
            COMMAND ${CMAKE_COMMAND} --build "${VERILATOR_BUILD}" --parallel ${N} --config Release
            RESULT_VARIABLE _build_res
            OUTPUT_VARIABLE _build_out
            ERROR_VARIABLE _build_err
    )
    if(NOT _build_res EQUAL 0)
        message(FATAL_ERROR "CMake build failed:\n${_build_err}")
    endif()


    # install step
    execute_process(
            COMMAND ${CMAKE_COMMAND} --install "${VERILATOR_BUILD}" --prefix ${VERILATOR_INSTALL}
            RESULT_VARIABLE _inst_res
            OUTPUT_VARIABLE _inst_out
            ERROR_VARIABLE _inst_err
    )
    if(NOT _inst_res EQUAL 0)
        message(FATAL_ERROR "CMake install failed:\n${_inst_err}")
    endif()

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