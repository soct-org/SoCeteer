cmake_minimum_required(VERSION 3.20)

# The SOCT System provides many variables used throughout the build process.
if (DEFINED SOCT_SYSTEM)
    cmake_path(IS_ABSOLUTE SOCT_SYSTEM _is_abs)
    if (NOT _is_abs)
        message(FATAL_ERROR "SOCT_SYSTEM variable must be an absolute path to a valid SOCTSystem.cmake file. Current value: ${SOCT_SYSTEM}")
    endif ()
    message(STATUS "Using SOCT system file from SOCT_SYSTEM variable: ${SOCT_SYSTEM}")
else ()
    cmake_path(GET CMAKE_CURRENT_LIST_DIR PARENT_PATH _temp)
    cmake_path(GET _temp PARENT_PATH _soceteer_root)
    cmake_path(APPEND _soceteer_root "SOCTSystem-latest.cmake" OUTPUT_VARIABLE _latest_soct_system) # Hardcoded fallback name (symlink) that SoCeteer creates for the latest system file

    if (NOT EXISTS "${_latest_soct_system}")
        message(FATAL_ERROR "SOCT_SYSTEM variable not defined or file not found, and fallback latest SOCT system file not found at ${_latest_soct_system}. Have you emitted a SoC system file from SoCeteer?")
    else ()
        message(WARNING "SOCT_SYSTEM variable not defined, but found fallback latest SOCT system file at ${_latest_soct_system}. Using this file as the SOCT system file.")
        set(SOCT_SYSTEM "${_latest_soct_system}")
    endif ()
endif ()