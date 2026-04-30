cmake_minimum_required(VERSION 3.20)

list(APPEND CMAKE_CXX_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.cpp)
include(${CMAKE_CURRENT_LIST_DIR}/../template.cmake)

######################################################################
# Apply Project specific flags - Modify this section to add more flags
######################################################################
target_compile_options(${SOCT_PROGRAM} PRIVATE -std=gnu11 -Os -O3)
target_include_directories(${SOCT_PROGRAM} PRIVATE
        ${SOCT_SYSTEM_ROOT}/gemmini-rocc-tests
)