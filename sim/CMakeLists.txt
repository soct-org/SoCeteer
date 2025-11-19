cmake_minimum_required(VERSION 3.20)

##############
# Debug prints
##############
if (NOT DEFINED SOCETEER_ROOT)
    execute_process(
            COMMAND git rev-parse --show-toplevel
            WORKING_DIRECTORY ${CMAKE_CURRENT_LIST_DIR}
            OUTPUT_VARIABLE SOCETEER_ROOT
            OUTPUT_STRIP_TRAILING_WHITESPACE
    )
endif ()

message(STATUS "SOCETEER_ROOT: ${SOCETEER_ROOT}")

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
list(PREPEND CMAKE_MODULE_PATH "${SOCETEER_ROOT}/shared/cmake")

find_package(VERILATOR MODULE REQUIRED)

message(STATUS "VERILATOR_ROOT: ${VERILATOR_ROOT}")
##################################################
# Find the configurations and include sub-projects
##################################################
# Where to find the verilog files for the different configurations
if (NOT DEFINED CONFIGS_IN_DIR)
    set(CONFIGS_IN_DIR "${CMAKE_CURRENT_LIST_DIR}/configs")
endif ()

# Find all the configurations in the CONFIGS_IN_DIR
file(GLOB CONFIGS RELATIVE "${CONFIGS_IN_DIR}" "${CONFIGS_IN_DIR}/*")

# Risc-v ISA simulator files
if(NOT DEFINED RV_ISA_SIM_DIR)
    set(RV_ISA_SIM_DIR ${SOCETEER_ROOT}/buildtools/risc-v-isa-sim)
endif()

message(STATUS "RV_ISA_SIM_DIR: ${RV_ISA_SIM_DIR}")
set(RV_ISA_SIM_INCLUDE_DIRS
        ${RV_ISA_SIM_DIR}/riscv
        ${RV_ISA_SIM_DIR}/softfloat
        ${SOCETEER_ROOT}/buildtools/libgloss/include/htif
        ${RV_ISA_SIM_DIR}/fesvr
)
file(GLOB DISASM_SRCS "${RV_ISA_SIM_DIR}/disasm/*.cc")
file(GLOB FESVR_SRCS "${RV_ISA_SIM_DIR}/fesvr/*.cpp")

list(APPEND RV_ISA_SIM_SRCS ${FESVR_SRCS} ${DISASM_SRCS})

#########################
# Set up Verilator params
#########################
if (NOT DEFINED VL_TOP_MODULE)
    set(VL_TOP_MODULE "SoctSimTop")
endif ()

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
    set(VL_THREADS 2)
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
foreach (CONFIG IN LISTS CONFIGS)
    set(CONFIG_DIR "${CONFIGS_IN_DIR}/${CONFIG}") # The directory containing the config
    set(VL_CONFIG_OUT "${CONFIG_DIR}/generated-src") # The directory to output the verilator generated files
    file(GLOB_RECURSE CONFIG_VERILOG_SOURCES "${CONFIG_DIR}/**/*.v" "${CONFIG_DIR}/*.v" "${CONFIG_DIR}/**/*.sv" "${CONFIG_DIR}/*.sv") # The verilog files for the config
    file(GLOB_RECURSE CANDIDATES LIST_DIRECTORIES true "${CONFIG_DIR}/*")
    foreach(path IN LISTS CANDIDATES)
        if(IS_DIRECTORY "${path}")
            list(APPEND VL_INCLUDE_DIR "${path}")
        endif()
    endforeach()
    file(GLOB CONFIG_CXX_SOURCES "${CONFIG_DIR}/*.cc" "${CONFIG_DIR}/*.cpp") # The C++ files for the config

    # The C++ sources for the config
    set(COMMON_CXX_SRCS
            ${CMAKE_CURRENT_LIST_DIR}/dpi-c.cpp
            ${RV_ISA_SIM_SRCS}
            ${CONFIG_CXX_SOURCES}
            ${CMAKE_CURRENT_LIST_DIR}/main.cpp
            ${CMAKE_CURRENT_LIST_DIR}/remote/bitbang.cpp
    )
    message(STATUS "Verilog files for ${CONFIG}: ${CONFIG_VERILOG_SOURCES}")
    message(STATUS "C++ files for ${CONFIG}: ${COMMON_CXX_SRCS}")
    message(STATUS "Verilator output directory: ${VL_CONFIG_OUT}")

    add_executable(${CONFIG} ${COMMON_CXX_SRCS})

    # Set the C++ standard and optimization flags
    if (MSVC) # 🤡
        if (CMAKE_BUILD_TYPE STREQUAL "Release")
            target_compile_options(${CONFIG} PRIVATE /O2)
        else ()
            target_compile_options(${CONFIG} PRIVATE /Od /RTC1)
        endif ()
        target_compile_options(${CONFIG} PRIVATE /std:c++20)
    else ()
        if (CMAKE_BUILD_TYPE STREQUAL "Release")
            target_compile_options(${CONFIG} PRIVATE -O3 -static -std=c++20 -march=native)
        else ()
            target_compile_options(${CONFIG} PRIVATE -O0 -static -std=c++20 -march=native)
        endif ()
    endif ()

    # Execute the verilator command
    verilate(${CONFIG}
            SOURCES ${CONFIG_VERILOG_SOURCES} ${CMAKE_CURRENT_LIST_DIR}/verilator_config.vlt
            DIRECTORY ${VL_CONFIG_OUT}
            PREFIX ${VL_PREFIX}
            TOP_MODULE ${VL_TOP_MODULE}
            INCLUDE_DIRS ${VL_INCLUDE_DIR}
            VERILATOR_ARGS ${VL_ARGS}
    )
    include(${VL_CONFIG_OUT}/${VL_PREFIX}.cmake) # Include the generated cmake file

    # Which include directories are available to the target
    target_include_directories(${CONFIG} PRIVATE
            ${RV_ISA_SIM_INCLUDE_DIRS}
            ${CMAKE_CURRENT_LIST_DIR}
    )

    target_compile_definitions(${CONFIG} PRIVATE
            VL_VERILATED_INCLUDE="custom-verilated.h" # Overrides the default verilated.h
            VL_PREFIX=${VL_PREFIX} # So we can include VL_PREFIX.h
    )

    if (WIN32)
        target_link_libraries(${CONFIG} PRIVATE ws2_32) # For windows sockets
        target_compile_definitions(${CONFIG} PRIVATE VL_TIME_CONTEXT) # According to the verilator manual.
    endif ()

    # Expose VL constants to the C++ code
    if (VL_TRACE)
        target_compile_definitions(${CONFIG} PRIVATE VL_TRACE)
    endif ()

    # Set the trace depth
    if (VL_TRACE_DEPTH)
        target_compile_definitions(${CONFIG} PRIVATE VL_TRACE_DEPTH=${VL_TRACE_DEPTH})
    endif ()

    # Pass unknown syscalls directly to OS
    if (PASS_UNKNOWN_SYSCALLS)
        target_compile_definitions(${CONFIG} PRIVATE PASS_UNKNOWN_SYSCALLS)
    endif ()

    # Enable logging level trace
    if (ENABLE_TRACE)
        target_compile_definitions(${CONFIG} PRIVATE ENABLE_TRACE)
    endif ()

    # Force assertions
    if (FORCE_ASSERTS)
        target_compile_definitions(${CONFIG} PRIVATE FORCE_ASSERTS)
    endif ()
endforeach ()