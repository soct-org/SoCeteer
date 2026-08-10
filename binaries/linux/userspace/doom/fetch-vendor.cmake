# Build-time fetch of the doom target's vendored inputs (cmake -P script, run by the
# custom command in CMakeLists.txt). Everything is hash-pinned; a present checkout is
# left alone, so after the first fetch the build works offline.
#
# Expects: -DSOCETEER_ROOT=<checkout root>

if (NOT DEFINED SOCETEER_ROOT)
    message(FATAL_ERROR "fetch-vendor.cmake needs -DSOCETEER_ROOT")
endif ()

# doomgeneric: the engine plus its five-function platform interface (GPL-2, which is
# why it is fetched instead of committed).
set(_dg_commit "dcb7a8dbc7a16ce3dda29382ac9aae9d77d21284")
set(_dg_sha256 "1bd3f7f26220494159a38d71f2847ec81b58d6bbd7c7c8d81b08993018001148")
set(_dg_url "https://github.com/ozkl/doomgeneric/archive/${_dg_commit}.tar.gz")
set(_dg_home "${SOCETEER_ROOT}/shared/vendor/doomgeneric")
set(_dg_src "${_dg_home}/src")
# The guard is a file the build DECLARES as an output, not the directory: the clean
# target deletes declared outputs (the engine .c files) while the directory and its
# headers survive, and a directory-existence guard would then skip the re-fetch
# forever. The tarball is kept after extraction for the same reason - recovery from
# a clean must not need the network.
if (NOT EXISTS "${_dg_src}/doomgeneric/doomgeneric.c")
    set(_dg_tar "${_dg_home}/doomgeneric-${_dg_commit}.tar.gz")
    if (NOT EXISTS "${_dg_tar}")
        message(STATUS "doom: fetching doomgeneric @${_dg_commit} (github.com/ozkl, ~3 MB)")
        file(DOWNLOAD "${_dg_url}" "${_dg_tar}" EXPECTED_HASH SHA256=${_dg_sha256} STATUS _dg_dl)
        list(GET _dg_dl 0 _dg_dl_code)
        if (NOT _dg_dl_code EQUAL 0)
            file(REMOVE "${_dg_tar}")
            message(FATAL_ERROR "doom: doomgeneric download failed (${_dg_dl})")
        endif ()
    endif ()
    file(REMOVE_RECURSE "${_dg_src}")
    file(ARCHIVE_EXTRACT INPUT "${_dg_tar}" DESTINATION "${_dg_home}")
    file(RENAME "${_dg_home}/doomgeneric-${_dg_commit}" "${_dg_src}")
    message(STATUS "doom: doomgeneric ready at ${_dg_src}")
endif ()

# Freedoom: a libre IWAD, so the game is playable without id's data.
set(_fd_ver "0.13.0")
set(_fd_sha256 "3f9b264f3e3ce503b4fb7f6bdcb1f419d93c7b546f4df3e874dd878db9688f59")
set(_fd_url "https://github.com/freedoom/freedoom/releases/download/v${_fd_ver}/freedoom-${_fd_ver}.zip")
set(_fd_home "${SOCETEER_ROOT}/shared/vendor/freedoom")
set(_fd_wad "${_fd_home}/freedoom1.wad")
if (NOT EXISTS "${_fd_wad}")
    set(_fd_zip "${_fd_home}/freedoom-${_fd_ver}.zip")
    if (NOT EXISTS "${_fd_zip}")
        message(STATUS "doom: fetching Freedoom ${_fd_ver} (github.com/freedoom, ~60 MB)")
        file(DOWNLOAD "${_fd_url}" "${_fd_zip}" EXPECTED_HASH SHA256=${_fd_sha256} STATUS _fd_dl)
        list(GET _fd_dl 0 _fd_dl_code)
        if (NOT _fd_dl_code EQUAL 0)
            file(REMOVE "${_fd_zip}")
            message(FATAL_ERROR "doom: Freedoom download failed (${_fd_dl})")
        endif ()
    endif ()
    file(ARCHIVE_EXTRACT INPUT "${_fd_zip}" DESTINATION "${_fd_home}"
            PATTERNS "*/freedoom1.wad" "*/COPYING.txt")
    file(RENAME "${_fd_home}/freedoom-${_fd_ver}/freedoom1.wad" "${_fd_wad}")
    file(RENAME "${_fd_home}/freedoom-${_fd_ver}/COPYING.txt" "${_fd_home}/COPYING.txt")
    file(REMOVE_RECURSE "${_fd_home}/freedoom-${_fd_ver}")
    file(REMOVE "${_fd_zip}")
    message(STATUS "doom: Freedoom ready at ${_fd_wad}")
endif ()
