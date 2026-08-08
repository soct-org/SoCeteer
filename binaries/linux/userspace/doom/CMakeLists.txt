cmake_minimum_required(VERSION 3.21)

# doom - the id Software engine on the framebuffer console, through doomgeneric
# (github.com/ozkl/doomgeneric): the engine plus a five-function platform layer,
# implemented here in doomgeneric_soct.c (framebuffer output) and soct_evdev.c
# (keyboard input).
#
# A standalone target, excluded from the default build: nothing else depends on it
# and no image packs it - build it explicitly (`--target doom`), then copy the ELF
# and freedoom1.wad from SOCT_ELFS_DIR onto the SD card and run it from /media.
#
# The engine is GPL-2 and therefore not committed to this repository: building the
# target fetches it hash-pinned into shared/vendor/ (fetch-vendor.cmake), together
# with the Freedoom IWAD - so a kernel-only configure and build touches neither the
# network nor the engine. This program (a derivative) is GPL-2 as well, scoped to
# this directory.
set(_dg_src "${SOCETEER_ROOT}/shared/vendor/doomgeneric/src")
set(_fd_wad "${SOCETEER_ROOT}/shared/vendor/freedoom/freedoom1.wad")

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

# The fetch as a build step: the engine sources are OUTPUTs of this command, so
# compiling any of them runs the fetch first, and a configure never touches the
# network. The stamp carries the dependency for the two local sources, whose
# doomgeneric.h include the compiler cannot know about before the tree exists.
set(_dg_stamp "${CMAKE_CURRENT_BINARY_DIR}/vendor.stamp")
add_custom_command(OUTPUT ${_dg_engine_srcs} "${_dg_stamp}"
        COMMAND ${CMAKE_COMMAND} -DSOCETEER_ROOT=${SOCETEER_ROOT}
                -P "${CMAKE_CURRENT_SOURCE_DIR}/fetch-vendor.cmake"
        COMMAND ${CMAKE_COMMAND} -E touch "${_dg_stamp}"
        COMMENT "Fetching doom vendor inputs (doomgeneric + Freedoom, first build only)"
        VERBATIM)
set_source_files_properties(
        "${CMAKE_CURRENT_SOURCE_DIR}/doomgeneric_soct.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/soct_evdev.c"
        PROPERTIES OBJECT_DEPENDS "${_dg_stamp}")

list(APPEND CMAKE_C_SRCS
        ${CMAKE_CURRENT_SOURCE_DIR}/doomgeneric_soct.c
        ${CMAKE_CURRENT_SOURCE_DIR}/soct_evdev.c
        ${_dg_engine_srcs})

include(${CMAKE_CURRENT_LIST_DIR}/../userspace.cmake)

set_target_properties(doom PROPERTIES EXCLUDE_FROM_ALL TRUE)

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
