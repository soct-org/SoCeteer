# FindVerilator.cmake

include(FindPackageHandleStandardArgs)

# Try to get VERILATOR_ROOT from environment if not set
if(NOT DEFINED VERILATOR_ROOT AND DEFINED ENV{VERILATOR_ROOT})
    set(VERILATOR_ROOT $ENV{VERILATOR_ROOT} CACHE PATH "Verilator root path")
endif()

# If still not set, use a default relative to project
if(NOT DEFINED VERILATOR_ROOT)
    set(VERILATOR_ROOT "${CMAKE_CURRENT_LIST_DIR}/../verilator" CACHE PATH "Verilator root path")
endif()

# Define executable based on platform
if(WIN32)
    set(VERILATOR_EXECUTABLE "${VERILATOR_ROOT}/bin/verilator_bin.exe")
else()
    set(VERILATOR_EXECUTABLE "${VERILATOR_ROOT}/bin/verilator_bin")
endif()

# Function to install Verilator on Windows
function(_install_verilator_windows)
    set(VERILATOR_BUILD_DIR "${VERILATOR_ROOT}/build")
    set(VERILATOR_INSTALL_DIR "${VERILATOR_ROOT}/install")

    if(NOT DEFINED WIN_FLEX_BISON AND DEFINED ENV{WIN_FLEX_BISON})
        set(WIN_FLEX_BISON $ENV{WIN_FLEX_BISON})
    elseif(NOT DEFINED WIN_FLEX_BISON)
        set(WIN_FLEX_BISON "C:\\ProgramData\\chocolatey\\lib\\winflexbison3\\tools")
    endif()

    message(STATUS "Installing Verilator in ${VERILATOR_INSTALL_DIR}")

    execute_process(COMMAND ${CMAKE_COMMAND} -E make_directory ${VERILATOR_BUILD_DIR})
    execute_process(
        COMMAND ${CMAKE_COMMAND} -S .. -B .
        COMMAND ${CMAKE_COMMAND} .. -DWIN_FLEX_BISON=${WIN_FLEX_BISON} -DCMAKE_BUILD_TYPE=Release
        COMMAND ${CMAKE_COMMAND} --build . --config Release
        COMMAND ${CMAKE_COMMAND} --install . --prefix ${VERILATOR_INSTALL_DIR}
        WORKING_DIRECTORY ${VERILATOR_BUILD_DIR}
    )

    set(VERILATOR_ROOT "${VERILATOR_INSTALL_DIR}" PARENT_SCOPE)
endfunction()

# Try to find or install
if(EXISTS "${VERILATOR_EXECUTABLE}")
    message(STATUS "Verilator installation: ${VERILATOR_EXECUTABLE}")
else()
    if(WIN32)
        _install_verilator_windows()
    else()
        message(FATAL_ERROR "Verilator not found at ${VERILATOR_EXECUTABLE}. Please install it or set VERILATOR_ROOT.")
    endif()
endif()

# Mark as found and expose variables
find_package_handle_standard_args(VERILATOR
    REQUIRED_VARS VERILATOR_ROOT VERILATOR_EXECUTABLE
    HANDLE_COMPONENTS
)

set(VERILATOR_FOUND TRUE)
