###############################
# This script locates or installs Verilator.
# It has the following precedence for finding a Verilator installation:
# 1. User-defined VERILATOR_ROOT variable (-DVERILATOR_ROOT=...)
# 2. VERILATOR_ROOT environment variable
# 3. The Verilator submodule relative to the project - VERILATOR_ROOT=../verilator
#
# 1. and 2. expect to find "$VERILATOR_ROOT/bin/verilator_bin[.exe]".
# If it does not exist or the version is too old / cannot be determined, this script will try to install at 3.
###############################

cmake_minimum_required(VERSION 3.20)
include(FindPackageHandleStandardArgs)

# Required Verilator version
set(VERILATOR_MAJOR_REQUIRED 5) # Major version must match exactly
set(VERILATOR_MINOR_REQUIRED 0) # Minor version must be at least this TODO: validate version

# The verilator shipped with the project (as a submodule)
file(REAL_PATH "${CMAKE_CURRENT_LIST_DIR}/../verilator" VERILATOR_SUBMODULE)

# 1.
if (DEFINED VERILATOR_ROOT)
    message(DEBUG "Using user-defined VERILATOR_ROOT: ${VERILATOR_ROOT}")
# 2.
elseif (DEFINED ENV{VERILATOR_ROOT})
    set(VERILATOR_ROOT $ENV{VERILATOR_ROOT})
    message(DEBUG "Using VERILATOR_ROOT from environment: ${VERILATOR_ROOT}")
# 3.
elseif (NOT EXISTS "${VERILATOR_SUBMODULE}")
    message(FATAL_ERROR "VERILATOR_ROOT not defined and no project-relative verilator submodule found at ${VERILATOR_SUBMODULE}.")
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
        # First regex matches a release binary installed via package managers, second regex matches a build from source like "rev v5.042-51-gd91574507"
        if (_verilator_version_output MATCHES "Verilator ([0-9]+)\\.([0-9]+)\\.*" OR _verilator_version_output MATCHES "rev v([0-9]+)\\.([0-9]+)\\.*")
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

include(${CMAKE_CURRENT_LIST_DIR}/install-verilator.cmake) # Provides install_verilator()

# Check if the found Verilator is of sufficient version
_check_verilator_version("${VERILATOR_EXE}" _verilator_ok)
if (NOT _verilator_ok)
    unset(VERILATOR_EXE)
endif()


# If not found or insufficient version, attempt to install from project submodule
if (NOT EXISTS "${VERILATOR_EXE}")
    message(STATUS "Verilator not found or insufficient version. Attempting to install from project submodule.")
    unset(VERILATOR_EXE)
    set(VERILATOR_SOURCE "${VERILATOR_SUBMODULE}")
    set(VERILATOR_INSTALL "${VERILATOR_SUBMODULE}/artifact")
    install_verilator()
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