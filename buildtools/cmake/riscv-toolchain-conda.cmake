if (NOT DEFINED SOCETEER_ROOT)
    # Parse the git root:
    execute_process(
            COMMAND git rev-parse --show-toplevel
            WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
            OUTPUT_VARIABLE SOCETEER_ROOT
            OUTPUT_STRIP_TRAILING_WHITESPACE
    )
endif ()

# Set the compiler toolchain and verify that it exists
if (DEFINED ENV{RISCV_TOOLS})
    set(RISCV_TOOLS $ENV{RISCV_TOOLS})
elseif (NOT DEFINED RISCV_TOOLS)
    set(RISCV_TOOLS ${SOCETEER_ROOT}/buildtools/conda/riscv-tools)
endif ()

function(install_riscv_tools)
    # check if CONDA_EXE is defined
    if (NOT DEFINED CONDA_EXE)
        if (DEFINED ENV{CONDA_EXE})
            set(CONDA_EXE "$ENV{CONDA_EXE}")
        else ()
            if (WIN32)
                list(APPEND CONDA_PATHS "C:\\Users\\$ENV{USERNAME}\\anaconda3\\Scripts\\conda.exe" "C:\\Users\\$ENV{USERNAME}\\miniconda3\\Scripts\\conda.exe")
            else ()
                list(APPEND CONDA_PATHS "/home/$ENV{USER}/miniconda3/bin/conda" "/home/$ENV{USER}/anaconda3/bin/conda")
            endif ()
            # check each path:
            foreach (CONDA_PATH ${CONDA_PATHS})
                if (EXISTS ${CONDA_PATH})
                    set(CONDA_EXE ${CONDA_PATH})
                    break()
                endif ()
            endforeach ()
            if (NOT EXISTS ${CONDA_EXE})
                message(FATAL_ERROR "CONDA_EXE not found at ${CONDA_EXE}. Please install conda and set the CONDA_EXE variable.")
            endif ()
        endif ()
    endif ()

    get_filename_component(CONDA_PREFIX ${RISCV_TOOLS} DIRECTORY)
    message(STATUS "Installing riscv-tools in ${CONDA_PREFIX}")
    # Define the Conda environment installation command
    execute_process(
            COMMAND ${CONDA_EXE} create --yes --prefix ${CONDA_PREFIX} -c ucb-bar -c conda-forge -c litex-hub --override-channels riscv-tools==1.0.3
            RESULT_VARIABLE conda_result
            ERROR_VARIABLE conda_error
    )
    if (NOT conda_result EQUAL 0)
        message(FATAL_ERROR "Failed to install riscv-tools. Error: ${conda_error}")
    else ()
        message(STATUS "riscv-tools installed successfully.")
    endif ()
endfunction()

# Check if the RISC-V tools are installed
if (NOT EXISTS ${RISCV_TOOLS})
    message(STATUS "RISC-V tools not found. Installing riscv-tools...")
    install_riscv_tools()
endif ()

# Where to find the compiler toolchain.
set(RV_PREFIX ${RISCV_TOOLS}/bin/riscv64-unknown-elf-)

set(CMAKE_CXX_LINUX_COMPILER ${RISCV_TOOLS}/bin/riscv64-unknown-linux-gnu-g++)

include(${CMAKE_CURRENT_LIST_DIR}/_riscv-toolchain.cmake)