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
            if (EXISTS "${WIN_FLEX_BISON}")
                message(STATUS "Using default Win Flex Bison path: ${WIN_FLEX_BISON}")
            else()
                # There is no way Verilator will succeed to find flex/bison on Windows without this, so we error out if the default path does not exist
                message(FATAL_ERROR "WIN_FLEX_BISON variable not defined and default path ${WIN_FLEX_BISON} does not exist. Please set WIN_FLEX_BISON to the path where Win Flex Bison is installed.")
            endif()
        endif()
        list(APPEND _cfg_cmd -DWIN_FLEX_BISON=${WIN_FLEX_BISON})
    elseif(APPLE)
        find_program(BREW_EXECUTABLE brew)
        if(BREW_EXECUTABLE)
            # On macOS, we check for brew install and add it to the cmake include/lib paths if it exists
            execute_process(COMMAND ${BREW_EXECUTABLE} --prefix flex
                    RESULT_VARIABLE _brew_flex_res
                    OUTPUT_VARIABLE _brew_flex_out
                    ERROR_VARIABLE _brew_flex_err)
            if(_brew_flex_res EQUAL 0)
                string(STRIP "${_brew_flex_out}" _brew_flex_out)
                list(APPEND _include_path ${_brew_flex_out}/include)
                list(APPEND _library_path ${_brew_flex_out}/lib)
            else()
                message(WARNING "Brew flex not found, relying on the Verilator's way to find flex. Error: ${_brew_flex_err}")
            endif()

            execute_process(COMMAND ${BREW_EXECUTABLE} --prefix bison
                    RESULT_VARIABLE _brew_bison_res
                    OUTPUT_VARIABLE _brew_bison_out
                    ERROR_VARIABLE _brew_bison_err)
            if(_brew_bison_res EQUAL 0)
                string(STRIP "${_brew_bison_out}" _brew_bison_out)
                list(APPEND _include_path ${_brew_bison_out}/include)
                list(APPEND _library_path ${_brew_bison_out}/lib)
            else()
                message(WARNING "Brew bison not found, relying on the Verilator's way to find bison. Error: ${_brew_bison_err}")
            endif()

            if(_include_path)
                list(JOIN _include_path ";" _include_path_str)
                list(APPEND _cfg_cmd -DCMAKE_INCLUDE_PATH=${_include_path_str})
             endif()

            if(_library_path)
                list(JOIN _library_path ";" _library_path_str)
                list(APPEND _cfg_cmd -DCMAKE_LIBRARY_PATH=${_library_path_str})
            endif()
        else()
            message(WARNING "Homebrew not found, relying on the Verilator's way to find flex and bison. Please install Homebrew to improve chances of Verilator finding flex and bison on macOS.")
        endif()
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