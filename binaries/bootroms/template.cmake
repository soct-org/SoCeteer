cmake_minimum_required(VERSION 3.20)

get_filename_component(BOOTROM_NAME ${CMAKE_CURRENT_SOURCE_DIR} NAME)

set(BASE_CFLAGS -mcmodel=medany -nostartfiles -DBAREMETAL -fno-common -Wall -Wextra)
set(BASE_LINKFLAGS -mcmodel=medany -DBAREMETAL -static -fno-common -Wall -Wextra)

message(STATUS "Adding bootrom: ${BOOTROM_NAME}")

add_executable(${BOOTROM_NAME} ${CMAKE_CXX_SRCS} ${CMAKE_C_SRCS})

target_compile_options(${BOOTROM_NAME} PRIVATE ${BASE_CFLAGS} -march=${MARCH} -mabi=${MABI})
