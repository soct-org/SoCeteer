cmake_minimum_required(VERSION 3.20)

# This file can be included from each program's CMakeLists.txt file to set up. Feel free to just copy-paste and modify
# Input sources: CMAKE_CXX_SRCS, CMAKE_C_SRCS
# Requires SOCT_SYSTEM

get_filename_component(PROGRAM_NAME ${CMAKE_CURRENT_SOURCE_DIR} NAME)

set(BASE_CFLAGS -mcmodel=medany -nostartfiles -DBAREMETAL -fno-common -Wall -Wextra)
set(BASE_LFLAGS -mcmodel=medany -DBAREMETAL -static -fno-common -Wall -Wextra)

message(STATUS "Adding Program: ${PROGRAM_NAME}")

add_executable(${PROGRAM_NAME} ${CMAKE_CXX_SRCS} ${CMAKE_C_SRCS})

# Link with libgloss_htif for the simulator target, and libgloss_board for the board target.
if(SOCT_TARGET STREQUAL "verilator")
    target_link_libraries(${PROGRAM_NAME} PRIVATE libgloss_htif)
    set(TARGET_CFLAGS ${LGLOSS_HTIF_CFLAGS})
    set(TARGET_LDFLAGS ${LGLOSS_HTIF_LDFLAGS})
elseif(SOCT_TARGET STREQUAL "vivado")
    target_link_libraries(${PROGRAM_NAME} PRIVATE libgloss_board)
    set(TARGET_CFLAGS ${LGLOSS_BOARD_CFLAGS})
    set(TARGET_LDFLAGS ${LGLOSS_BOARD_LDFLAGS})
else()
    message(FATAL_ERROR "Unknown SOCT_TARGET: ${SOCT_TARGET}.")
endif()

target_compile_options(${PROGRAM_NAME} PRIVATE ${BASE_CFLAGS} -march=${SOCT_ARCH} -mabi=${SOCT_ABI} ${TARGET_CFLAGS})
target_link_options(${PROGRAM_NAME} PRIVATE ${BASE_LFLAGS} -march=${SOCT_ARCH} -mabi=${SOCT_ABI} ${TARGET_LDFLAGS})
target_include_directories(${PROGRAM_NAME} PRIVATE ${LIBGLOSS_DIR}/include)
set_target_properties(${PROGRAM_NAME} PROPERTIES OUTPUT_NAME ${PROGRAM_NAME}.elf RUNTIME_OUTPUT_DIRECTORY ${SOCT_ELFS_DIR})