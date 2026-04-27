cmake_minimum_required(VERSION 3.20)

list(APPEND CMAKE_C_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.c)

include(${CMAKE_CURRENT_LIST_DIR}/../template.cmake)

######################################################################
# Apply Project specific flags - Modify this section to add more flags
######################################################################
target_compile_options(${SOCT_PROGRAM} PRIVATE -std=gnu11 -O3 -Os)
target_compile_definitions(${SOCT_PROGRAM} PRIVATE N_CORES=${SOCT_NCPUS})
