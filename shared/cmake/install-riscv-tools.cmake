###################################################
# Function to install RISC-V Tools
# Requires:
# RISCV_TOOLS - path where to install the RISC-V toolchain (must not exist or be empty).
# RISCV_TOOLS_VERSION - version of the RISC-V toolchain to install.
# The directory will be created if it does not exist already.
# Outputs:
# Nothing
###################################################
cmake_minimum_required(VERSION 3.20)

function(install_riscv_tools)
    # Validate RISCV_TOOLS directory
    if (NOT DEFINED RISCV_TOOLS)
        message(FATAL_ERROR "RISCV_TOOLS variable is not defined. Please set it to the path where to install the RISC-V toolchain.")
    endif ()
    if (EXISTS ${RISCV_TOOLS})
        file(GLOB _existing_files "${RISCV_TOOLS}/*")
        if (_existing_files)
            message(FATAL_ERROR "RISCV_TOOLS directory ${RISCV_TOOLS} already exists and is not empty. Please set RISCV_TOOLS to an empty or non-existing directory.")
        endif ()
    endif ()


    # Determine the platform (linux-arm, linux-arm64, linux-x64, darwin-x64, darwin-arm64, windows-x64)
    if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
        if (CMAKE_HOST_SYSTEM_PROCESSOR STREQUAL "aarch64")
            set(PLATFORM "linux-arm64")
        elseif (CMAKE_HOST_SYSTEM_PROCESSOR MATCHES "^arm")
            set(PLATFORM "linux-arm")
        else ()
            set(PLATFORM "linux-x64")
        endif ()
    elseif (CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
        if (CMAKE_HOST_SYSTEM_PROCESSOR STREQUAL "arm64")
            set(PLATFORM "darwin-arm64")
        else ()
            set(PLATFORM "darwin-x64")
        endif ()
    elseif (CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
        set(PLATFORM "windows-x64")
    else ()
        message(FATAL_ERROR "Unsupported platform: ${CMAKE_HOST_SYSTEM_NAME} ${CMAKE_HOST_SYSTEM_PROCESSOR}")
    endif ()

    # Download the toolchain
    cmake_path(GET RISCV_TOOLS PARENT_PATH RISCV_TOOLS_DOWNLOAD_DIR)
    set(RISCV_TOOLS_FULL_NAME "xpack-riscv-none-elf-gcc-${RISCV_TOOLS_VERSION}") # Also the name of the extracted archive
    set(RISCV_TOOLS_ARCHIVE_NAME "${RISCV_TOOLS_FULL_NAME}-${PLATFORM}.tar.gz")
    set(RISCV_TOOLS_URL "https://github.com/xpack-dev-tools/riscv-none-elf-gcc-xpack/releases/download/v${RISCV_TOOLS_VERSION}/${RISCV_TOOLS_ARCHIVE_NAME}")
    set(RISCV_TOOLS_URL_SHA ${RISCV_TOOLS_URL}.sha)

    file(MAKE_DIRECTORY ${RISCV_TOOLS_DOWNLOAD_DIR})

    message(STATUS "Downloading RISC-V toolchain from ${RISCV_TOOLS_URL} to ${RISCV_TOOLS_DOWNLOAD_DIR}")

    set(_archive_path "${RISCV_TOOLS_DOWNLOAD_DIR}/riscv-none-elf-gcc.tar.gz")
    set(_sha_path "${_archive_path}.sha256")

    # Download the hash first
    file(DOWNLOAD
            ${RISCV_TOOLS_URL_SHA}
            ${_sha_path}
            STATUS _download_status_sha
            LOG log_download_sha.txt
    )

    list(GET _download_status_sha 0 _status_code_sha)
    if (NOT _status_code_sha EQUAL 0)
        message(FATAL_ERROR "Failed to download RISC-V toolchain SHA256 from ${RISCV_TOOLS_URL_SHA}. See log_download_sha.txt for details.")
    endif ()

    # Content has the format: <hash> <filename>
    file(READ ${_sha_path} _sha_contents)
    string(REGEX REPLACE "[\r\n]+" "" _sha_contents "${_sha_contents}")
    string(REGEX MATCH "^[a-fA-F0-9]+" _expected_hash "${_sha_contents}")
    string(REGEX MATCH "[^ ]+$" _expected_archive "${_sha_contents}")

    # match the expected filename to the archive name
    if (NOT _expected_archive STREQUAL ${RISCV_TOOLS_ARCHIVE_NAME})
        message(FATAL_ERROR "SHA256 filename mismatch: expected ${RISCV_TOOLS_ARCHIVE_NAME}, got ${_expected_archive}")
    endif ()

    # Download the archive
    file(DOWNLOAD
            ${RISCV_TOOLS_URL}
            ${_archive_path}
            EXPECTED_HASH SHA256=${_expected_hash}
            SHOW_PROGRESS
            STATUS _download_status
            LOG log_download.txt
    )

    list(GET _download_status 0 _status_code)
    if (NOT _status_code EQUAL 0)
        message(FATAL_ERROR "Failed to download RISC-V toolchain from ${RISCV_TOOLS_URL}. See log_download.txt for details.")
    endif ()

    # Extract the archive
    message(STATUS "Extracting RISC-V toolchain to ${RISCV_TOOLS_DOWNLOAD_DIR}")

    file(ARCHIVE_EXTRACT INPUT ${_archive_path} DESTINATION ${RISCV_TOOLS_DOWNLOAD_DIR})

    # Rename the extracted directory to a consistent name (has the same name as the archive without .tar.gz)
    set(_extracted_dir_path "${RISCV_TOOLS_DOWNLOAD_DIR}/${RISCV_TOOLS_FULL_NAME}")
    file(RENAME ${_extracted_dir_path} ${RISCV_TOOLS})

    # Clean up
    file(REMOVE ${_archive_path})
    file(REMOVE ${_sha_path})
endfunction()

# If run in Script mode, run installation
if (CMAKE_SCRIPT_MODE_FILE)
    if (NOT DEFINED RISCV_TOOLS_VERSION)
        message(FATAL_ERROR "RISCV_TOOLS_VERSION variable is not defined. Please set it to the version of the RISC-V toolchain to install.")
    endif ()
    install_riscv_tools()
endif ()