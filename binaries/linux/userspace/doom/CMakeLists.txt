cmake_minimum_required(VERSION 3.21)

# doom - the id Software engine on the framebuffer console, through doomgeneric
# (github.com/ozkl/doomgeneric): the engine plus a five-function platform layer,
# implemented here in doomgeneric_soct.c (framebuffer output) and soct_evdev.c
# (keyboard input).
#
# The engine is GPL-2 and therefore not committed to this repository: it is
# fetched hash-pinned into shared/vendor/ at configure time, like busybox. This
# program (a derivative) is GPL-2 as well, scoped to this directory.
set(_dg_commit "dcb7a8dbc7a16ce3dda29382ac9aae9d77d21284")
set(_dg_sha256 "1bd3f7f26220494159a38d71f2847ec81b58d6bbd7c7c8d81b08993018001148")
set(_dg_url "https://github.com/ozkl/doomgeneric/archive/${_dg_commit}.tar.gz")
set(_dg_home "${SOCETEER_ROOT}/shared/vendor/doomgeneric")
set(_dg_src "${_dg_home}/src")
if (NOT EXISTS "${_dg_src}")
    set(_dg_tar "${_dg_home}/doomgeneric-${_dg_commit}.tar.gz")
    if (NOT EXISTS "${_dg_tar}")
        message(STATUS "linux: fetching doomgeneric @${_dg_commit} (github.com/ozkl, ~3 MB)")
        file(DOWNLOAD "${_dg_url}" "${_dg_tar}" EXPECTED_HASH SHA256=${_dg_sha256} STATUS _dg_dl)
        list(GET _dg_dl 0 _dg_dl_code)
        if (NOT _dg_dl_code EQUAL 0)
            file(REMOVE "${_dg_tar}")
            message(FATAL_ERROR "linux: doomgeneric download failed (${_dg_dl})")
        endif ()
    endif ()
    file(ARCHIVE_EXTRACT INPUT "${_dg_tar}" DESTINATION "${_dg_home}")
    file(RENAME "${_dg_home}/doomgeneric-${_dg_commit}" "${_dg_src}")
    file(REMOVE "${_dg_tar}")
    message(STATUS "linux: doomgeneric ready at ${_dg_src}")
endif ()

# Freedoom: a libre IWAD, so the game is playable without id's data. The WAD
# lands next to the ELFs for copying onto the SD card; the game finds it under
# /media at runtime. A real doom WAD on the card works the same way.
set(_fd_ver "0.13.0")
set(_fd_sha256 "3f9b264f3e3ce503b4fb7f6bdcb1f419d93c7b546f4df3e874dd878db9688f59")
set(_fd_url "https://github.com/freedoom/freedoom/releases/download/v${_fd_ver}/freedoom-${_fd_ver}.zip")
set(_fd_home "${SOCETEER_ROOT}/shared/vendor/freedoom")
set(_fd_wad "${_fd_home}/freedoom1.wad")
if (NOT EXISTS "${_fd_wad}")
    set(_fd_zip "${_fd_home}/freedoom-${_fd_ver}.zip")
    if (NOT EXISTS "${_fd_zip}")
        message(STATUS "linux: fetching Freedoom ${_fd_ver} (github.com/freedoom, ~60 MB)")
        file(DOWNLOAD "${_fd_url}" "${_fd_zip}" EXPECTED_HASH SHA256=${_fd_sha256} STATUS _fd_dl)
        list(GET _fd_dl 0 _fd_dl_code)
        if (NOT _fd_dl_code EQUAL 0)
            file(REMOVE "${_fd_zip}")
            message(FATAL_ERROR "linux: Freedoom download failed (${_fd_dl})")
        endif ()
    endif ()
    file(ARCHIVE_EXTRACT INPUT "${_fd_zip}" DESTINATION "${_fd_home}"
            PATTERNS "*/freedoom1.wad" "*/COPYING.txt")
    file(RENAME "${_fd_home}/freedoom-${_fd_ver}/freedoom1.wad" "${_fd_wad}")
    file(RENAME "${_fd_home}/freedoom-${_fd_ver}/COPYING.txt" "${_fd_home}/COPYING.txt")
    file(REMOVE_RECURSE "${_fd_home}/freedoom-${_fd_ver}")
    file(REMOVE "${_fd_zip}")
    message(STATUS "linux: Freedoom ready at ${_fd_wad}")
endif ()

# The engine sources: doomgeneric's Makefile SRC_DOOM list, minus the platform
# main (doomgeneric_xlib.c) that this directory replaces.
set(_dg_engine
        dummy am_map doomdef doomstat dstrings d_event d_items d_iwad d_loop d_main
        d_mode d_net f_finale f_wipe g_game hu_lib hu_stuff info i_cdmus i_endoom
        i_joystick i_scale i_sound i_system i_timer memio m_argv m_bbox m_cheat
        m_config m_controls m_fixed m_menu m_misc m_random p_ceilng p_doors p_enemy
        p_floor p_inter p_lights p_map p_maputl p_mobj p_plats p_pspr p_saveg p_setup
        p_sight p_spec p_switch p_telept p_tick p_user r_bsp r_data r_draw r_main
        r_plane r_segs r_sky r_things sha1 sounds statdump st_lib st_stuff s_sound
        tables v_video wi_stuff w_checksum w_file w_main w_wad z_zone w_file_stdc
        i_input i_video doomgeneric)
set(_dg_engine_srcs "")
foreach (_f IN LISTS _dg_engine)
    list(APPEND _dg_engine_srcs "${_dg_src}/doomgeneric/${_f}.c")
endforeach ()

list(APPEND CMAKE_C_SRCS
        ${CMAKE_CURRENT_SOURCE_DIR}/doomgeneric_soct.c
        ${CMAKE_CURRENT_SOURCE_DIR}/soct_evdev.c
        ${_dg_engine_srcs})

include(${CMAKE_CURRENT_LIST_DIR}/../userspace.cmake)

target_include_directories(doom PRIVATE "${_dg_src}/doomgeneric")
# The engine's own build flags, plus the native 320x200 render size - the
# platform layer integer-upscales onto the active mode.
# CMAP256: the engine keeps its native 8bpp output and the platform layer
# expands palette indices straight to scaled 24bpp - one conversion, not two.
target_compile_definitions(doom PRIVATE
        NORMALUNIX LINUX SNDSERV _DEFAULT_SOURCE CMAP256
        DOOMGENERIC_RESX=320 DOOMGENERIC_RESY=200)
# Vendored 1993-vintage C; its warnings are not ours to fix.
set_source_files_properties(${_dg_engine_srcs} PROPERTIES COMPILE_OPTIONS "-w")

add_custom_command(TARGET doom POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different "${_fd_wad}" "${SOCT_ELFS_DIR}/freedoom1.wad"
        BYPRODUCTS "${SOCT_ELFS_DIR}/freedoom1.wad"
        COMMENT "Installing freedoom1.wad to ${SOCT_ELFS_DIR} - copy to the SD card for doom"
        VERBATIM)
