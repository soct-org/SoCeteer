###############################
# This script locates or installs the RISC-V toolchain.
# It has the following precedence for finding the toolchain:
# 1. User-defined RISCV_TOOLS variable (-DRISCV_TOOLS=...)
# 2. RISCV_TOOLS environment variable
# 3. Default path relative to the project (tries to download it)
###############################

if (NOT DEFINED RISCV_TOOLS_DOWNLOAD_DIR)
    get_filename_component(RISCV_TOOLS_DOWNLOAD_DIR "${CMAKE_CURRENT_LIST_DIR}/../vendor" ABSOLUTE)
endif ()

set(RISCV_TOOLS_PROJECT_PATH "${RISCV_TOOLS_DOWNLOAD_DIR}/riscv-none-elf-gcc")

set(RISCV_TOOLS_VERSION "15.2.0-1") # Version to download if not found

# 1.
if (DEFINED RISCV_TOOLS)
    message(STATUS "Using user-defined RISCV_TOOLS: ${RISCV_TOOLS}")
    # 2.
elseif (DEFINED ENV{RISCV_TOOLS})
    set(RISCV_TOOLS $ENV{RISCV_TOOLS})
    message(STATUS "Using RISCV_TOOLS from environment: ${RISCV_TOOLS}")
    # 3.
elseif (EXISTS "${RISCV_TOOLS_PROJECT_PATH}")
    get_filename_component(RISCV_TOOLS "${RISCV_TOOLS_PROJECT_PATH}" ABSOLUTE)
    message(STATUS "Using project-relative RISCV_TOOLS: ${RISCV_TOOLS}")
else ()
    message(STATUS "Using default RISCV_TOOLS_DOWNLOAD_DIR: ${RISCV_TOOLS_DOWNLOAD_DIR}. To override, set RISCV_TOOLS_DOWNLOAD_DIR.")
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

    # Create vendor directory if it doesn't exist
    file(MAKE_DIRECTORY ${RISCV_TOOLS_DOWNLOAD_DIR})

    # Download the toolchain
    set(RISCV_TOOLS_FULL_NAME "xpack-riscv-none-elf-gcc-${RISCV_TOOLS_VERSION}") # Also the name of the extracted archive
    set(RISCV_TOOLS_ARCHIVE_NAME "${RISCV_TOOLS_FULL_NAME}-${PLATFORM}.tar.gz")
    set(RISCV_TOOLS_URL "https://github.com/xpack-dev-tools/riscv-none-elf-gcc-xpack/releases/download/v${RISCV_TOOLS_VERSION}/${RISCV_TOOLS_ARCHIVE_NAME}")
    set(RISCV_TOOLS_URL_SHA ${RISCV_TOOLS_URL}.sha)

    message(STATUS "Downloading RISC-V toolchain from ${RISCV_TOOLS_URL}")

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
    file(RENAME ${_extracted_dir_path} ${RISCV_TOOLS_PROJECT_PATH})

    get_filename_component(RISCV_TOOLS "${RISCV_TOOLS_PROJECT_PATH}" ABSOLUTE)

    # Clean up
    file(REMOVE ${_archive_path})
    file(REMOVE ${_sha_path})
endif ()


# Where to find the compiler toolchain.
set(RV_PREFIX ${RISCV_TOOLS}/bin/riscv-none-elf-)

include(${CMAKE_CURRENT_LIST_DIR}/_riscv-toolchain.cmake)