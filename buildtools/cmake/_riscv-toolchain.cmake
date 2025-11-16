# This toolchain file must not be included directly!

# Function to parse the version of a tool
function(get_tool_version tool variable)
    execute_process(
            COMMAND ${tool} --version
            OUTPUT_VARIABLE version_output
            OUTPUT_STRIP_TRAILING_WHITESPACE
    )
    if (version_output MATCHES ".* ([0-9]+\\.[0-9]+\\.[0-9]+).*")
        set(${variable} "${CMAKE_MATCH_1}" PARENT_SCOPE)
    else ()
        set(${variable} "unknown" PARENT_SCOPE)
    endif ()
endfunction()

# Set the compiler toolchain and verify that it exists
set(CMAKE_C_COMPILER "${RV_PREFIX}gcc")
set(CMAKE_CXX_COMPILER "${RV_PREFIX}g++")
set(CMAKE_ASM_COMPILER "${RV_PREFIX}gcc")
set(CMAKE_AR "${RV_PREFIX}ar")
set(CMAKE_SIZE "${RV_PREFIX}size")

# Get versions
get_tool_version(${CMAKE_C_COMPILER} C_COMPILER_VERSION)
get_tool_version(${CMAKE_CXX_COMPILER} CXX_COMPILER_VERSION)
get_tool_version(${CMAKE_ASM_COMPILER} ASM_COMPILER_VERSION)
get_tool_version(${CMAKE_AR} AR_VERSION)
get_tool_version(${CMAKE_SIZE} SIZE_VERSION)

# DO NOT TOUCH THIS LINE - Needed for RocketLauncher to find the toolchain
message(STATUS "RISC-V toolchain prefix: ${RV_PREFIX}")

if (NOT EXISTS "${CMAKE_C_COMPILER}")
    message(FATAL_ERROR "The C compiler was not found at ${CMAKE_C_COMPILER}. Please set the RV_PREFIX variable to the path to the RISC-V toolchain.")
else ()
    message(STATUS "Using C compiler: ${CMAKE_C_COMPILER} (version ${C_COMPILER_VERSION})")
endif ()

if (NOT EXISTS "${CMAKE_CXX_COMPILER}")
    message(FATAL_ERROR "The C++ compiler was not found at ${CMAKE_CXX_COMPILER}. Please set the RV_PREFIX variable to the path to the RISC-V toolchain.")
else ()
    message(STATUS "Using C++ compiler: ${CMAKE_CXX_COMPILER} (version ${CXX_COMPILER_VERSION})")
endif ()

if (NOT EXISTS "${CMAKE_ASM_COMPILER}")
    message(FATAL_ERROR "The assembler was not found at ${CMAKE_ASM_COMPILER}. Please set the RV_PREFIX variable to the path to the RISC-V toolchain.")
else ()
    message(STATUS "Using assembler: ${CMAKE_ASM_COMPILER} (version ${ASM_COMPILER_VERSION})")
endif ()

if (NOT EXISTS "${CMAKE_AR}")
    message(FATAL_ERROR "The archiver was not found at ${CMAKE_AR}. Please set the RV_PREFIX variable to the path to the RISC-V toolchain.")
else ()
    message(STATUS "Using archiver: ${CMAKE_AR} (version ${AR_VERSION})")
endif ()

if (NOT EXISTS "${CMAKE_SIZE}")
    message(FATAL_ERROR "The size utility was not found at ${CMAKE_SIZE}. Please set the RV_PREFIX variable to the path to the RISC-V toolchain.")
else ()
    message(STATUS "Using size utility: ${CMAKE_SIZE} (version ${SIZE_VERSION})")
endif ()

# Set the sysroot
execute_process(
        COMMAND ${CMAKE_C_COMPILER} -print-sysroot
        OUTPUT_VARIABLE CMAKE_SYSROOT
        OUTPUT_STRIP_TRAILING_WHITESPACE
)

message(STATUS "Using sysroot: ${CMAKE_SYSROOT}")

# Set the system name and processor
set(CMAKE_SYSTEM_NAME Generic)
set(CMAKE_SYSTEM_PROCESSOR riscv${XLEN})

# Set the find root path
set(CMAKE_FIND_ROOT_PATH ${SYSROOT})
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)