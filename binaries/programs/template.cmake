cmake_minimum_required(VERSION 3.20)

# This file can be included from each program's CMakeLists.txt file to set up. Feel free to just copy-paste and modify
# Input sources: CMAKE_CXX_SRCS, CMAKE_C_SRCS
# Input flags: NO_SIM - if set, do not build the simulation target. NO_BOARD - if set, do not build the board target.
#              NO_32 - if set, do not build the 32-bit target. NO_64 - if set, do not build the 64-bit target.
# ELF_DIR - directory to place output ELF files
# Other input flags: MARCH64, MABI64, MARCH32, MABI32

get_filename_component(PROGRAM_NAME ${CMAKE_CURRENT_SOURCE_DIR} NAME)
set(PROGRAM_SIM_NAME ${PROGRAM_NAME}-sim)
set(PROGRAM_SIM_NAME_32 ${PROGRAM_SIM_NAME}-32)
set(PROGRAM_SIM_NAME_64 ${PROGRAM_SIM_NAME}-64)

set(PROGRAM_BOARD_NAME ${PROGRAM_NAME}-board)
set(PROGRAM_BOARD_NAME_32 ${PROGRAM_BOARD_NAME}-32)
set(PROGRAM_BOARD_NAME_64 ${PROGRAM_BOARD_NAME}-64)

# Contains all programs build for simulation and board:
set(PROGRAM_SIM_NAMES "")
set(PROGRAM_BOARD_NAMES "")

set(BASE_CFLAGS -mcmodel=medany -nostartfiles -DBAREMETAL -fno-common -Wall -Wextra)
set(BASE_LINKFLAGS -mcmodel=medany -DBAREMETAL -static -fno-common -Wall -Wextra)

message(STATUS "Adding Program: ${PROGRAM_NAME}")

# SIMULATION
if (NOT DEFINED NO_SIM)
    if (NOT DEFINED NO_64)
        list(APPEND PROGRAM_SIM_NAMES ${PROGRAM_SIM_NAME_64})
        add_executable(${PROGRAM_SIM_NAME_64} ${CMAKE_CXX_SRCS} ${CMAKE_C_SRCS})
        target_link_libraries(${PROGRAM_SIM_NAME_64} PRIVATE libgloss64_htif)
        target_compile_options(${PROGRAM_SIM_NAME_64} PRIVATE ${BASE_CFLAGS} -march=${MARCH64} -mabi=${MABI64} ${LGLOSS_HTIF_CFLAGS_64})
        target_link_options(${PROGRAM_SIM_NAME_64} PRIVATE ${BASE_LINKFLAGS} -march=${MARCH64} -mabi=${MABI64} ${LGLOSS_HTIF_LDFLAGS_64})
        target_include_directories(${PROGRAM_SIM_NAME_64} PRIVATE ${LIBGLOSS_DIR}/include)
        set_target_properties(${PROGRAM_SIM_NAME_64} PROPERTIES
                OUTPUT_NAME boot-sim.elf
                RUNTIME_OUTPUT_DIRECTORY ${ELF_DIR}/${PROGRAM_NAME})
    endif ()

    if (NOT DEFINED NO_32)
        list(APPEND PROGRAM_SIM_NAMES ${PROGRAM_SIM_NAME_32})
        add_executable(${PROGRAM_SIM_NAME_32} ${CMAKE_CXX_SRCS} ${CMAKE_C_SRCS})
        target_link_libraries(${PROGRAM_SIM_NAME_32} PRIVATE libgloss32_htif)
        target_compile_options(${PROGRAM_SIM_NAME_32} PRIVATE ${BASE_CFLAGS} -march=${MARCH32} -mabi=${MABI32} ${LGLOSS_HTIF_CFLAGS_32})
        target_link_options(${PROGRAM_SIM_NAME_32} PRIVATE ${BASE_LINKFLAGS} -march=${MARCH32} -mabi=${MABI32} ${LGLOSS_HTIF_LDFLAGS_32})
        target_include_directories(${PROGRAM_SIM_NAME_32} PRIVATE ${LIBGLOSS_DIR}/include)
        set_target_properties(${PROGRAM_SIM_NAME_32} PROPERTIES
                OUTPUT_NAME boot-sim-32.elf
                RUNTIME_OUTPUT_DIRECTORY ${ELF_DIR}/${PROGRAM_NAME})
    endif ()
endif()

# BOARD
if (NOT DEFINED NO_BOARD)
    if (NOT DEFINED NO_64)
        list(APPEND PROGRAM_BOARD_NAMES ${PROGRAM_BOARD_NAME_64})
        add_executable(${PROGRAM_BOARD_NAME_64} ${CMAKE_CXX_SRCS} ${CMAKE_C_SRCS})
        target_link_libraries(${PROGRAM_BOARD_NAME_64} PRIVATE libgloss64_board)
        target_compile_options(${PROGRAM_BOARD_NAME_64} PRIVATE ${BASE_CFLAGS} -march=${MARCH64} -mabi=${MABI64} ${LGLOSS_BOARD_CFLAGS_64})
        target_link_options(${PROGRAM_BOARD_NAME_64} PRIVATE ${BASE_LINKFLAGS} -march=${MARCH64} -mabi=${MABI64} ${LGLOSS_BOARD_LDFLAGS_64})
        target_include_directories(${PROGRAM_BOARD_NAME_64} PRIVATE ${LIBGLOSS_DIR}/include)
        set_target_properties(${PROGRAM_BOARD_NAME_64} PROPERTIES
                OUTPUT_NAME boot-board.elf
                RUNTIME_OUTPUT_DIRECTORY ${ELF_DIR}/${PROGRAM_NAME})
    endif ()

    if (NOT DEFINED NO_32)
        list(APPEND PROGRAM_BOARD_NAMES ${PROGRAM_BOARD_NAME_32})
        add_executable(${PROGRAM_BOARD_NAME_32} ${CMAKE_CXX_SRCS} ${CMAKE_C_SRCS})
        target_link_libraries(${PROGRAM_BOARD_NAME_32} PRIVATE libgloss32_board)
        target_compile_options(${PROGRAM_BOARD_NAME_32} PRIVATE ${BASE_CFLAGS} -march=${MARCH32} -mabi=${MABI32} ${LGLOSS_BOARD_CFLAGS_32})
        target_link_options(${PROGRAM_BOARD_NAME_32} PRIVATE ${BASE_LINKFLAGS} -march=${MARCH32} -mabi=${MABI32} ${LGLOSS_BOARD_LDFLAGS_32})
        target_include_directories(${PROGRAM_BOARD_NAME_32} PRIVATE ${LIBGLOSS_DIR}/include)
        set_target_properties(${PROGRAM_BOARD_NAME_32} PROPERTIES
                OUTPUT_NAME boot-board-32.elf
                RUNTIME_OUTPUT_DIRECTORY ${ELF_DIR}/${PROGRAM_NAME})
    endif ()
endif()

# Add target PROGRAM_SIM_NAME that builds in PROGRAM_SIM_NAMES
if (PROGRAM_SIM_NAMES)
    add_custom_target(${PROGRAM_SIM_NAME} DEPENDS ${PROGRAM_SIM_NAMES})
endif ()
# Add target PROGRAM_BOARD_NAME that builds in PROGRAM_BOARD_NAMES
if (PROGRAM_BOARD_NAMES)
    add_custom_target(${PROGRAM_BOARD_NAME} DEPENDS ${PROGRAM_BOARD_NAMES})
endif ()