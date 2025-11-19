cmake_minimum_required(VERSION 3.20)

file(GLOB CMAKE_ASM_SRCS ${CMAKE_CURRENT_LIST_DIR}/*.S)
file(GLOB CMAKE_C_SRCS ${CMAKE_CURRENT_LIST_DIR}/*.c)

set(LFLAGS -T ${CMAKE_CURRENT_LIST_DIR}/bootrom.lds)

include(${CMAKE_CURRENT_LIST_DIR}/../template.cmake)
