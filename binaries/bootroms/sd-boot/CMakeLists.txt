cmake_minimum_required(VERSION 3.20)

file(GLOB CMAKE_ASM_SRCS ${CMAKE_CURRENT_LIST_DIR}/*.S)
file(GLOB CMAKE_C_SRCS ${CMAKE_CURRENT_LIST_DIR}/*.c)

set(BOOTROM_SCRATCH_SIZE 0x8000) # BOOTROM_MEM_END - SOCT_MEM_BASE_ADDR
math(EXPR BOOTROM_MEM_END "${SOCT_MEM_BASE_ADDR} + ${BOOTROM_SCRATCH_SIZE}" OUTPUT_FORMAT HEXADECIMAL)
# place scratch at top of ext RAM, leaving 8 KiB guard
math(EXPR _BOOTROM_MEM_ALT_RAW "${SOCT_MEM_BASE_ADDR} + ${SOCT_EXT_MEM_SIZE} - 0x2000 - ${BOOTROM_SCRATCH_SIZE}")
math(EXPR _BOOTROM_MEM_ALT_HEX "${_BOOTROM_MEM_ALT_RAW}" OUTPUT_FORMAT HEXADECIMAL)

if (SOCT_XLEN EQUAL 32 AND _BOOTROM_MEM_ALT_RAW GREATER 0xFFFFFFFF)
    message(FATAL_ERROR
            "sd-boot: BOOTROM_MEM_ALT=${_BOOTROM_MEM_ALT_HEX} exceeds 32-bit address space with SOCT_XLEN=32. "
            "Adjust SOCT_MEM_BASE_ADDR/SOCT_EXT_MEM_SIZE, or use RV64.")
endif ()
math(EXPR BOOTROM_MEM_ALT "${_BOOTROM_MEM_ALT_RAW}" OUTPUT_FORMAT HEXADECIMAL)

if (SOCT_XLEN EQUAL 32)
    set(_mcmodel "medany")
else ()
    set(_mcmodel "large")
endif ()

list(APPEND CFLAGS
        -mcmodel=${_mcmodel}
        -DBOOTROM_MEM_END=${BOOTROM_MEM_END}
        -DBOOTROM_MEM_ALT=${BOOTROM_MEM_ALT}
)

set(LFLAGS
        -mcmodel=${_mcmodel}
        # The shared linker script sizes the DRAM scratch region from this
        "LINKER:--defsym=BOOTROM_SCRATCH_SIZE=${BOOTROM_SCRATCH_SIZE}"
)

include(${CMAKE_CURRENT_LIST_DIR}/../template.cmake)
