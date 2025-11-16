if (NOT DEFINED SOCETEER_ROOT)
    # Parse the git root:
    execute_process(
            COMMAND git rev-parse --show-toplevel
            WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
            OUTPUT_VARIABLE SOCETEER_ROOT
            OUTPUT_STRIP_TRAILING_WHITESPACE
    )
endif ()

if (NOT DEFINED RISCV_VERSION)
    set(RISCV_VERSION "14.2.0-3")
endif ()

# Set the vendor directory for the RISC-V tools
if (DEFINED ENV{VENDOR_DIR})
    set(VENDOR_DIR $ENV{VENDOR_DIR})
elseif (NOT DEFINED VENDOR_DIR)
    set(VENDOR_DIR ${SOCETEER_ROOT}/buildtools/vendor)
endif ()

# Set the compiler toolchain and verify that it exists
if (DEFINED ENV{RISCV_TOOLS})
    set(RISCV_TOOLS $ENV{RISCV_TOOLS})
elseif (NOT DEFINED RISCV_TOOLS)
    set(RISCV_TOOLS ${VENDOR_DIR}/riscv-none-elf-gcc)
endif ()

# Set the scripts directory
if (NOT DEFINED SCRIPTS_DIR)
    set(SCRIPTS_DIR ${SOCETEER_ROOT}/scripts/install-deps)
endif ()

function(install_riscv_tools)
     if (WIN32)
        set(INSTALL_SCRIPT ${SCRIPTS_DIR}/install-rv-compiler.bat)
    else ()
        set(INSTALL_SCRIPT ${SCRIPTS_DIR}/install-rv-compiler.sh)
    endif ()

    message(STATUS "Installing RISC-V toolchain using script: ${INSTALL_SCRIPT} in ${VENDOR_DIR}. Version: ${RISCV_VERSION}")

    execute_process(
        COMMAND ${INSTALL_SCRIPT} ${VENDOR_DIR} ${RISCV_VERSION}
        RESULT_VARIABLE install_result
        ERROR_VARIABLE install_error
    )

    # Check if the installation was successful
    if (NOT EXISTS ${RISCV_TOOLS})
        message(FATAL_ERROR "Failed to install RISC-V tools.")
    else ()
        message(STATUS "RISC-V tools installed successfully.")
    endif ()

endfunction()

# Check if the RISC-V tools are installed
if (NOT EXISTS ${RISCV_TOOLS})
    message(STATUS "RISC-V tools not found. Installing compiler toolchain.")
    install_riscv_tools()
endif ()

# Where to find the compiler toolchain.
set(RV_PREFIX ${RISCV_TOOLS}/bin/riscv-none-elf-)

include(${CMAKE_CURRENT_LIST_DIR}/_riscv-toolchain.cmake)