cmake_minimum_required(VERSION 3.20)


include(${CMAKE_CURRENT_LIST_DIR}/../template.cmake)


add_executable(bootrom_testchipip "${TESTCHIPIP_DIR}/bootrom.S")