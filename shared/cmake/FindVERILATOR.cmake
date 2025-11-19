###############################
# This script locates or installs Verilator.
# It has the following precedence for finding Verilator:
# 1. User-defined VERILATOR_ROOT variable (-DVERILATOR_ROOT=...)
# 2. VERILATOR_ROOT environment variable
# 3. Default path relative to the project (tries to build it)
#
# 1. and 2. expect to find "bin/verilator_bin[.exe]" there. If the version is too old or cannot be determined, this script will jump to 3.
###############################

include(FindPackageHandleStandardArgs)

# Required Verilator version
set(VERILATOR_MAJOR_REQUIRED 5) # Major version must match exactly
set(VERILATOR_MINOR_REQUIRED 0) # Minor version must be at least this TODO: validate version

# The verilator shipped with the project (as a submodule)
set(VERILATOR_PROJECT_PATH "${CMAKE_CURRENT_LIST_DIR}/../verilator")

# 1.
if (DEFINED VERILATOR_ROOT)
    message(STATUS "Using user-defined VERILATOR_ROOT: ${VERILATOR_ROOT}")
    # 2.
elseif (DEFINED ENV{VERILATOR_ROOT})
    set(VERILATOR_ROOT $ENV{VERILATOR_ROOT})
    message(STATUS "Using VERILATOR_ROOT from environment: ${VERILATOR_ROOT}")
    # 3.
elseif (EXISTS "${VERILATOR_PROJECT_PATH}")
    get_filename_component(VERILATOR_ROOT "${VERILATOR_PROJECT_PATH}" ABSOLUTE)
    message(STATUS "Using project-relative VERILATOR_ROOT: ${VERILATOR_ROOT}")
else ()
    message(FATAL_ERROR "VERILATOR_ROOT not defined and no project-relative verilator submodule found at ${VERILATOR_PROJECT_PATH}. Please define VERILATOR_ROOT to point to a valid Verilator installation.")
endif ()


# Define executable based on platform
if (WIN32)
    set(VERILATOR_EXE "${VERILATOR_ROOT}/bin/verilator_bin.exe")
else ()
    set(VERILATOR_EXE "${VERILATOR_ROOT}/bin/verilator_bin")
endif ()


#####################################
# Function to check Verilator version
# sets result_var to TRUE if version is sufficient, FALSE otherwise
#####################################
function(_check_verilator_version exe result_var)
    if (EXISTS "${exe}")
        execute_process(
                COMMAND "${exe}" --version
                OUTPUT_VARIABLE _verilator_version_output
                OUTPUT_STRIP_TRAILING_WHITESPACE
                ERROR_QUIET
        )
        string(STRIP "${_verilator_version_output}" _verilator_version_output)

        if (_verilator_version_output MATCHES "Verilator ([0-9]+)\\.([0-9]+)\\.*")
            set(_major "${CMAKE_MATCH_1}")
            set(_minor "${CMAKE_MATCH_2}")

            message(STATUS "Found Verilator version: ${_major}.${_minor}")

            if (NOT "${_major}" STREQUAL "${VERILATOR_MAJOR_REQUIRED}")
                message(WARNING "Verilator major version ${_major} does not match required ${VERILATOR_MAJOR_REQUIRED}. Attempting to install correct version.")
                set(${result_var} FALSE PARENT_SCOPE)
            elseif (${_minor} LESS ${VERILATOR_MINOR_REQUIRED})
                message(WARNING "Verilator minor version ${_minor} is less than required ${VERILATOR_MINOR_REQUIRED}. Attempting to install correct version.")
                set(${result_var} FALSE PARENT_SCOPE)
            else()
                message(STATUS "Verilator version is sufficient.")
                set(${result_var} TRUE PARENT_SCOPE)
            endif()
        else()
            message(WARNING "Could not determine Verilator version from output: ${_verilator_version_output}. Attempting to install correct version.")
            set(${result_var} FALSE PARENT_SCOPE)
        endif()
    else()
        set(${result_var} FALSE PARENT_SCOPE)
    endif()
endfunction()

############################################
# Function to install Verilator if not found
############################################
function(_install_verilator)
    set(build_dir "${VERILATOR_ROOT}/build")
    set(install_dir "${VERILATOR_ROOT}/install")

    message(STATUS "Installing Verilator in ${install_dir}")

    # Determine number of processors for parallel build. Default to 2 if cannot be determined.
    include(ProcessorCount)
    ProcessorCount(N)
    if(NOT N EQUAL 0)
        math(EXPR N "${N} - 1") # Use one less than available processors - freezes system otherwise
    else()
        set(N 2)
    endif()

    # Windows needs Flex and Bison installed separately - use environment variable or default Chocolatey path
    if (WIN32)
        if (NOT DEFINED WIN_FLEX_BISON AND DEFINED ENV{WIN_FLEX_BISON})
            set(WIN_FLEX_BISON $ENV{WIN_FLEX_BISON})
        elseif (NOT DEFINED WIN_FLEX_BISON)
            set(WIN_FLEX_BISON "C:\\ProgramData\\chocolatey\\lib\\winflexbison3\\tools")
        endif ()
        message(STATUS "Using WIN_FLEX_BISON: ${WIN_FLEX_BISON}")
    endif ()

    execute_process(COMMAND ${CMAKE_COMMAND} -E make_directory ${build_dir})

    # configure step
    execute_process(
            COMMAND ${CMAKE_COMMAND} -S "${VERILATOR_PROJECT_PATH}" -B "${build_dir}"
                    $<$<BOOL:${WIN32}>:-DWIN_FLEX_BISON=${WIN_FLEX_BISON}>
                    -DCMAKE_BUILD_TYPE=Release
            RESULT_VARIABLE _cfg_res
            OUTPUT_VARIABLE _cfg_out
            ERROR_VARIABLE _cfg_err
    )
    if(NOT _cfg_res EQUAL 0)
        message(FATAL_ERROR "CMake configure failed:\n${_cfg_err}")
    endif()

    # build step
    execute_process(
            COMMAND ${CMAKE_COMMAND} --build "${build_dir}" --parallel ${N} --config Release
            RESULT_VARIABLE _build_res
            OUTPUT_VARIABLE _build_out
            ERROR_VARIABLE _build_err
    )
    if(NOT _build_res EQUAL 0)
        message(FATAL_ERROR "CMake build failed:\n${_build_err}")
    endif()


    # install step
    execute_process(
            COMMAND ${CMAKE_COMMAND} --install "${build_dir}" --prefix ${install_dir}
            RESULT_VARIABLE _inst_res
            OUTPUT_VARIABLE _inst_out
            ERROR_VARIABLE _inst_err
    )
    if(NOT _inst_res EQUAL 0)
        message(FATAL_ERROR "CMake install failed:\n${_inst_err}")
    endif()

    # store executable path in a local variable, then export to parent scope
    if (WIN32)
        set(_verilator_exec "${install_dir}/bin/verilator_bin.exe")
    else ()
        set(_verilator_exec "${install_dir}/bin/verilator_bin")
    endif()

    set(VERILATOR_ROOT "${install_dir}" PARENT_SCOPE)
    set(VERILATOR_EXE "${_verilator_exec}" PARENT_SCOPE)
endfunction()



# Check if the found Verilator is of sufficient version
_check_verilator_version("${VERILATOR_EXE}" _verilator_ok)
if (NOT _verilator_ok)
    unset(VERILATOR_EXE)
endif()


# If not found or insufficient version, attempt to install from project submodule
if (NOT EXISTS "${VERILATOR_EXE}")
    message(STATUS "Verilator not found or insufficient version. Attempting to install from project submodule.")
    unset(VERILATOR_EXE)
    _install_verilator()
    if (NOT EXISTS "${VERILATOR_EXE}")
        message(FATAL_ERROR "Verilator installation failed. Executable not found at ${VERILATOR_EXE}")
    endif ()
endif ()

message(STATUS "VERILATOR_EXE: ${VERILATOR_EXE}") # DO NOT REMOVE - SoCeteer uses this message to parse the path

# Mark as found and expose variables
find_package_handle_standard_args(VERILATOR
        REQUIRED_VARS VERILATOR_ROOT VERILATOR_EXE
        HANDLE_COMPONENTS
)

set(VERILATOR_FOUND TRUE)