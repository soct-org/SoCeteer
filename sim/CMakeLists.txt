cmake_minimum_required(VERSION 3.20)

############################################################################
# SOCT_CMAKE_PATH is the path to the shared cmake files in the SoCeteer repo
############################################################################
if (NOT DEFINED SOCT_CMAKE_PATH)
    cmake_path(GET CMAKE_CURRENT_LIST_DIR PARENT_PATH _soceteer_root)
    file(REAL_PATH "${_soceteer_root}/shared/cmake" SOCT_CMAKE_PATH)
    message(STATUS "SOCT_CMAKE_PATH: ${SOCT_CMAKE_PATH}")
endif ()
list(PREPEND CMAKE_MODULE_PATH ${SOCT_CMAKE_PATH})

find_package(SOCTSystem REQUIRED) # Provides SOCT_SYSTEM variable pointing to the system file with important variables for the project
message(STATUS "SOCT_SYSTEM: ${SOCT_SYSTEM}")
include(${SOCT_SYSTEM}) # Include the system file to get important variables for the project

if (NOT SOCT_TARGET STREQUAL "verilator")
    message(FATAL_ERROR "This CMakeLists.txt is only intended to be used for the verilator target. Current SOCT_TARGET: ${SOCT_TARGET}")
endif ()

# Set the project name
project(Verilate CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

message(STATUS "C compiler: ${CMAKE_C_COMPILER}")
message(STATUS "C++ compiler: ${CMAKE_CXX_COMPILER}")
message(STATUS "ASM compiler: ${CMAKE_ASM_COMPILER}")
message(STATUS "LD: ${CMAKE_LINKER}")


########################################
# Find project installation of Verilator
########################################
find_package(VERILATOR MODULE REQUIRED) # Installs/locates Verilator & provides VERILATOR_ROOT
find_package(VERILATOR REQUIRED HINTS ${VERILATOR_ROOT}) # Provides verilate() function (includes verilator-config.cmake)

##################################################
# Find the configurations and include sub-projects
##################################################
# Risc-v ISA simulator files
if(NOT DEFINED RV_ISA_SIM_DIR)
    set(RV_ISA_SIM_DIR ${SOCETEER_ROOT}/shared/risc-v-isa-sim)
endif()

message(STATUS "RV_ISA_SIM_DIR: ${RV_ISA_SIM_DIR}")
set(RV_ISA_SIM_INCLUDE_DIRS
        ${RV_ISA_SIM_DIR}/riscv
        ${RV_ISA_SIM_DIR}/softfloat
        ${SOCETEER_ROOT}/shared/libgloss/include/htif
        ${RV_ISA_SIM_DIR}/fesvr
)
file(GLOB DISASM_SRCS "${RV_ISA_SIM_DIR}/disasm/*.cc")
file(GLOB FESVR_SRCS "${RV_ISA_SIM_DIR}/fesvr/*.cpp")

list(APPEND RV_ISA_SIM_SRCS ${FESVR_SRCS} ${DISASM_SRCS})

#########################
# Set up Verilator params
#########################
# The name used in the C++ code to include VL_PREFIX.h
if (NOT DEFINED VL_PREFIX)
    set(VL_PREFIX "SystemTop")
endif ()

############################
# Set up Verilator arguments
############################
set(VL_ARGS "--vpi" "--O3" "-timing")

# The number of threads to use in verilator
if (NOT DEFINED VL_THREADS)
    # For now, set it to SOCT_NCPUS + 1 but at we should find a sensible upper limit
    math(EXPR VL_THREADS "${SOCT_NCPUS} + 1")
    message(STATUS "Using ${VL_THREADS} threads in verilator")
endif ()
list(APPEND VL_ARGS "--threads" "${VL_THREADS}")

if (DEFINED VL_TRACE)
    message(STATUS "Enabling verilator trace")
    list(APPEND VL_ARGS "--trace")
endif ()

if (DEFINED VL_TRACE_DEPTH)
    message(STATUS "Setting verilator trace depth to ${VL_TRACE_DEPTH}")
    list(APPEND VL_ARGS "--trace-depth" "${VL_TRACE_DEPTH}")
elseif (DEFINED VL_TRACE)
    set(VL_TRACE_DEPTH 2)
    message(STATUS "Setting default verilator trace depth to ${VL_TRACE_DEPTH}")
    list(APPEND VL_ARGS "--trace-depth" "${VL_TRACE_DEPTH}")
endif ()

message(STATUS "Verilator arguments: ${VL_ARGS}")

##################################
# Speed up compilation with ccache
##################################
find_program(CCACHE_FOUND ccache)
if(CCACHE_FOUND)
    set_property(GLOBAL PROPERTY RULE_LAUNCH_COMPILE ccache)
    set_property(GLOBAL PROPERTY RULE_LAUNCH_LINK ccache)
endif()

#################################
# Create targets from each config
#################################
file(GLOB_RECURSE VERILOG_SOURCES "${SOCT_VSRCS}/**/*.v" "${SOCT_VSRCS}/*.v" "${SOCT_VSRCS}/**/*.sv" "${SOCT_VSRCS}/*.sv")
cmake_path(APPEND SOCT_BUILD_DIR "verilated" OUTPUT_VARIABLE VERILATED_OUT_DIR)

file(GLOB_RECURSE CANDIDATES LIST_DIRECTORIES true "${SOCT_VSRCS}/*")
foreach(path IN LISTS CANDIDATES)
    if(IS_DIRECTORY "${path}")
        list(APPEND VL_INCLUDE_DIR "${path}")
    endif()
endforeach()
file(GLOB COLLATERAL_CXX_SRCS "${SOCT_VSRCS}/*.cc" "${SOCT_VSRCS}/*.cpp")

# The C++ sources for the config
set(COMMON_CXX_SRCS
        ${CMAKE_CURRENT_LIST_DIR}/dpi-c.cpp
        ${RV_ISA_SIM_SRCS}
        ${COLLATERAL_CXX_SRCS}
        ${CMAKE_CURRENT_LIST_DIR}/main.cpp
        ${CMAKE_CURRENT_LIST_DIR}/remote/bitbang.cpp
)
message(DEBUG "Verilog files for ${SOCT_SIM_EXE}: ${CONFIG_VERILOG_SOURCES}")
message(DEBUG "C++ files for ${SOCT_SIM_EXE}: ${COMMON_CXX_SRCS}")
message(DEBUG "Verilator output directory: ${VERILATED_OUT_DIR}")

add_executable(${SOCT_SIM_EXE} ${COMMON_CXX_SRCS})

if (CMAKE_BUILD_TYPE STREQUAL "Release")
    if (WIN32)
        if (MSVC)
            target_compile_options(${SOCT_SIM_EXE} PRIVATE /O2)
        else () # mingw
            target_compile_options(${SOCT_SIM_EXE} PRIVATE -O3 -march=native -static)
            target_link_options(${SOCT_SIM_EXE} PRIVATE -static -static-libgcc -static-libstdc++)
        endif ()
    else() # Unix
        target_compile_options(${SOCT_SIM_EXE} PRIVATE -O3 -static -march=native)
    endif ()
else () # Debug mode
    if (WIN32)
        if (MSVC)
            target_compile_options(${SOCT_SIM_EXE} PRIVATE /Od /RTC1)
        else () # mingw
            target_compile_options(${SOCT_SIM_EXE} PRIVATE -O0 -march=native -static)
            target_link_options(${SOCT_SIM_EXE} PRIVATE -static -static-libgcc -static-libstdc++)
        endif ()
    else()
        target_compile_options(${SOCT_SIM_EXE} PRIVATE -O0 -static -march=native)
    endif ()
endif ()

message(STATUS "Building target ${SOCT_SIM_EXE} with Verilator. This may take a while...")
# Execute the verilator command
verilate(${SOCT_SIM_EXE}
        SOURCES ${VERILOG_SOURCES} ${CMAKE_CURRENT_LIST_DIR}/verilator_config.vlt
        DIRECTORY ${VERILATED_OUT_DIR}
        PREFIX ${VL_PREFIX}
        INCLUDE_DIRS ${VL_INCLUDE_DIR}
        VERILATOR_ARGS ${VL_ARGS}
)
include(${VERILATED_OUT_DIR}/${VL_PREFIX}.cmake) # Include the generated cmake file

# Which include directories are available to the target
target_include_directories(${SOCT_SIM_EXE} PRIVATE
        ${RV_ISA_SIM_INCLUDE_DIRS}
        ${CMAKE_CURRENT_LIST_DIR}
)

target_compile_definitions(${SOCT_SIM_EXE} PRIVATE
        VL_VERILATED_INCLUDE="custom-verilated.h" # Overrides the default verilated.h
        VL_PREFIX=${VL_PREFIX} # So we can include VL_PREFIX.h
)

if (WIN32)
    target_link_libraries(${SOCT_SIM_EXE} PRIVATE ws2_32) # For windows sockets
    target_compile_definitions(${SOCT_SIM_EXE} PRIVATE VL_TIME_CONTEXT) # According to the verilator manual.
endif ()

# Expose VL constants to the C++ code
if (VL_TRACE)
    target_compile_definitions(${SOCT_SIM_EXE} PRIVATE VL_TRACE)
endif ()

# Set the trace depth
if (VL_TRACE_DEPTH)
    target_compile_definitions(${SOCT_SIM_EXE} PRIVATE VL_TRACE_DEPTH=${VL_TRACE_DEPTH})
endif ()

# Pass unknown syscalls directly to OS
if (PASS_UNKNOWN_SYSCALLS)
    target_compile_definitions(${SOCT_SIM_EXE} PRIVATE PASS_UNKNOWN_SYSCALLS)
endif ()

# Enable logging level trace
if (ENABLE_TRACE)
    target_compile_definitions(${SOCT_SIM_EXE} PRIVATE ENABLE_TRACE)
endif ()

# Force assertions
if (FORCE_ASSERTS)
    target_compile_definitions(${SOCT_SIM_EXE} PRIVATE FORCE_ASSERTS)
endif ()