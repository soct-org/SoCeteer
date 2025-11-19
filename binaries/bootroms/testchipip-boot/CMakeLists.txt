cmake_minimum_required(VERSION 3.20)


set(CMAKE_ASM_SRCS ${CMAKE_CURRENT_LIST_DIR}/bootrom.S)

set(LFLAGS -T ${CMAKE_CURRENT_LIST_DIR}/linker.ld)


include(${CMAKE_CURRENT_LIST_DIR}/../template.cmake)
