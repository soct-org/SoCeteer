cmake_minimum_required(VERSION 3.20)

list(APPEND CMAKE_C_SRCS
        ${CMAKE_CURRENT_SOURCE_DIR}/main.c
        ${CMAKE_CURRENT_SOURCE_DIR}/xhci.c
)

# The USB controller lives in the processing system, which does not respond to a
# register access until psu_init has configured its clocks, MIO and PHY.
include(SoctPsuInit)

include(${CMAKE_CURRENT_LIST_DIR}/../soctglue-static.cmake)

######################################################################
# Apply Project specific flags - Modify this section to add more flags
######################################################################
target_compile_options(${SOCT_PROGRAM} PRIVATE -std=gnu11 -O2)
