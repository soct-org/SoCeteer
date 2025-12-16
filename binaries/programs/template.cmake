cmake_minimum_required(VERSION 3.20)

# This file can be included from each program's CMakeLists.txt file to set up. Feel free to just copy-paste and modify
# Input sources: CMAKE_CXX_SRCS, CMAKE_C_SRCS
# Input flags: NO_SIM - if set, do not build the simulation target. NO_BOARD - if set, do not build the board target.
# ELF_DIR - directory to place output ELF files
# Other input flags: SOCT_ARCH, SOCT_ABI, SOCT_XLEN

get_filename_component(PROGRAM_NAME ${CMAKE_CURRENT_SOURCE_DIR} NAME)
set(PROGRAM_SIM_NAME ${PROGRAM_NAME}-sim)
set(PROGRAM_BOARD_NAME ${PROGRAM_NAME}-board)

set(BASE_CFLAGS -mcmodel=medany -nostartfiles -DBAREMETAL -fno-common -Wall -Wextra)
set(BASE_LFLAGS -mcmodel=medany -DBAREMETAL -static -fno-common -Wall -Wextra)

message(STATUS "Adding Program: ${PROGRAM_NAME}")

if (NOT DEFINED NO_SIM)
    add_executable(${PROGRAM_SIM_NAME} ${CMAKE_CXX_SRCS} ${CMAKE_C_SRCS})
    target_link_libraries(${PROGRAM_SIM_NAME} PRIVATE libgloss_htif)
    target_compile_options(${PROGRAM_SIM_NAME} PRIVATE ${BASE_CFLAGS} -march=${SOCT_ARCH} -mabi=${SOCT_ABI} ${LGLOSS_HTIF_CFLAGS})
    target_link_options(${PROGRAM_SIM_NAME} PRIVATE ${BASE_LFLAGS} -march=${SOCT_ARCH} -mabi=${SOCT_ABI} ${LGLOSS_HTIF_LDFLAGS})
    target_include_directories(${PROGRAM_SIM_NAME} PRIVATE ${LIBGLOSS_DIR}/include)
    set_target_properties(${PROGRAM_SIM_NAME} PROPERTIES
            OUTPUT_NAME boot-sim.elf
            RUNTIME_OUTPUT_DIRECTORY ${ELF_DIR}/programs/${PROGRAM_NAME})
endif()

if (NOT DEFINED NO_BOARD)
    list(APPEND PROGRAM_BOARD_NAMES ${PROGRAM_BOARD_NAME})
    add_executable(${PROGRAM_BOARD_NAME} ${CMAKE_CXX_SRCS} ${CMAKE_C_SRCS})
    target_link_libraries(${PROGRAM_BOARD_NAME} PRIVATE libgloss_board)
    target_compile_options(${PROGRAM_BOARD_NAME} PRIVATE ${BASE_CFLAGS} -march=${SOCT_ARCH} -mabi=${SOCT_ABI} ${LGLOSS_BOARD_CFLAGS})
    target_link_options(${PROGRAM_BOARD_NAME} PRIVATE ${BASE_LFLAGS} -march=${SOCT_ARCH} -mabi=${SOCT_ABI} ${LGLOSS_BOARD_LDFLAGS})
    target_include_directories(${PROGRAM_BOARD_NAME} PRIVATE ${LIBGLOSS_DIR}/include)
    set_target_properties(${PROGRAM_BOARD_NAME} PROPERTIES
            OUTPUT_NAME boot-board.elf
            RUNTIME_OUTPUT_DIRECTORY ${ELF_DIR}/programs/${PROGRAM_NAME})
endif()