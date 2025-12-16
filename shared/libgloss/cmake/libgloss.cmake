include_guard(GLOBAL)
cmake_minimum_required(VERSION 3.20)

set(LGLOSS_DIR ${CMAKE_CURRENT_LIST_DIR}/..)
set(LGLOSS_BUILD ${CMAKE_BINARY_DIR}/libgloss)

######################################
# libc extensions used by libgloss
######################################
if (NOT DEFINED SOCT_NO_PRINT_WRAP)
    message(STATUS "Using wrap_io.specs to wrap puts, printf, sprintf, snprintf")
    set(LGLOSS_WRAP_IO_FLAG -specs=${LGLOSS_DIR}/util/wrap_io.specs)
else ()
    set(LGLOSS_WRAP_IO_FLAG "")
endif ()

set(LGLOSS_LIBC -lc_nano)

###########################
# Libgloss HTIF (common)
###########################
set(LGLOSS_HTIF_DIR ${LGLOSS_DIR}/htif)
set(LGLOSS_HTIF_LD_SCRIPT ${LGLOSS_HTIF_DIR}/util/htif.ld)

set(_HTIF_CFLAGS_COMMON
        -mcmodel=medany
        -fno-builtin
        -fno-common
        -Wall
        -Wextra
        ${LGLOSS_WRAP_IO_FLAG}
)

macro(_compose_htif_ldflags _outvar _glosslib)
    set(${_outvar}
            -L${LGLOSS_BUILD}
            -T${LGLOSS_HTIF_LD_SCRIPT}
            -static
            ${LGLOSS_WRAP_IO_FLAG}
            -nostartfiles
            -Wl,--start-group
            ${LGLOSS_LIBC}
            -l${_glosslib}
            -Wl,--end-group
            -lgcc
    )
endmacro()

###############
# Libgloss HTIF
###############
set(LGLOSS_HTIF_LIB gloss_htif)

set(LGLOSS_HTIF_CFLAGS_64 ${_HTIF_CFLAGS_COMMON})

_compose_htif_ldflags(LGLOSS_HTIF_LDFLAGS ${LGLOSS_HTIF_LIB})

if (NOT DEFINED NO_HOST_ARGV)
    message(STATUS "Using htif_argv symbols for argument passing")
    list(APPEND LGLOSS_HTIF_LDFLAGS
            -Wl,--defsym=_start_main=_start_main_argv
            -Wl,--defsym=_start_secondary=_start_secondary_argv
    )
endif ()


###########################
# Libgloss BOARD (common)
###########################
set(LGLOSS_BOARD_DIR ${LGLOSS_DIR}/board)
set(LGLOSS_BOARD_LD_SCRIPT ${LGLOSS_BOARD_DIR}/util/board.ld)

set(_BOARD_CFLAGS_COMMON
        -mcmodel=medany
        -fno-builtin
        -fno-common
        -fPIC
        -Wall
        -Wextra
        ${LGLOSS_WRAP_IO_FLAG}
)

macro(_compose_board_ldflags _outvar _glosslib)
    set(${_outvar}
            -L${LGLOSS_BUILD}
            -T${LGLOSS_BOARD_LD_SCRIPT}
            -static
            ${LGLOSS_WRAP_IO_FLAG}
            -nostartfiles
            -Wl,--start-group
            ${LGLOSS_LIBC}
            -l${_glosslib}
            -Wl,--end-group
            -lgcc
    )
endmacro()

################
# Libgloss BOARD
################
set(LGLOSS_BOARD_LIB gloss_board)

set(LGLOSS_BOARD_CFLAGS ${_BOARD_CFLAGS_COMMON})

_compose_board_ldflags(LGLOSS_BOARD_LDFLAGS ${LGLOSS_BOARD_LIB})
