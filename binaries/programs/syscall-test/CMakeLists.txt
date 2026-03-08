cmake_minimum_required(VERSION 3.20)

list(APPEND CMAKE_CXX_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.cpp)

# Only makes sense for the verilator target
if (SOCT_TARGET STREQUAL "verilator")
    include(${CMAKE_CURRENT_LIST_DIR}/../template.cmake)

    ######################################################################
    # Apply Project specific flags - Modify this section to add more flags
    ######################################################################
    target_compile_options(${PROGRAM_NAME} PRIVATE -std=gnu++20 -O3 -Os)
endif ()
