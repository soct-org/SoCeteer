# BusyBox: a prebuilt static riscv64 userland for the shell boot image. Building BusyBox
# from source would mean a second kbuild frontier for one binary; Debian's busybox-static
# package is a hash-pinned, bit-exact stand-in (static glibc, rv64gc - runs on any
# SoCeteer core). Fetched once into shared/vendor/ from snapshot.debian.org, whose URLs
# are permanent - pool URLs disappear when a package version is superseded.
#
# Consumes: SOCETEER_ROOT. Defines: SOCT_BUSYBOX (path to the extracted binary).

set(_busybox_deb_name "busybox-static_1.37.0-6+b9_riscv64.deb")
set(_busybox_sha256 "4add476d2b185c5b487c285b38d790f0881a7e4a8e2ddde835f917e770eb8633")
set(_busybox_url "https://snapshot.debian.org/archive/debian/20260712T202631Z/pool/main/b/busybox/busybox-static_1.37.0-6%2Bb9_riscv64.deb")
set(SOCT_BUSYBOX "${SOCETEER_ROOT}/shared/vendor/busybox/busybox")
if (NOT EXISTS "${SOCT_BUSYBOX}")
    set(_busybox_deb "${SOCETEER_ROOT}/shared/vendor/busybox/${_busybox_deb_name}")
    if (NOT EXISTS "${_busybox_deb}")
        message(STATUS "linux: fetching ${_busybox_deb_name} (snapshot.debian.org, ~1 MB)")
        file(DOWNLOAD "${_busybox_url}" "${_busybox_deb}"
                EXPECTED_HASH SHA256=${_busybox_sha256} STATUS _bb_dl)
        list(GET _bb_dl 0 _bb_dl_code)
        if (NOT _bb_dl_code EQUAL 0)
            file(REMOVE "${_busybox_deb}")
            message(FATAL_ERROR "linux: busybox download failed (${_bb_dl})")
        endif ()
    endif ()
    # A .deb is an ar archive carrying the payload as data.tar.*; libarchive (behind
    # ARCHIVE_EXTRACT) reads both layers.
    set(_bb_x "${CMAKE_CURRENT_BINARY_DIR}/busybox-extract")
    file(REMOVE_RECURSE "${_bb_x}")
    file(ARCHIVE_EXTRACT INPUT "${_busybox_deb}" DESTINATION "${_bb_x}")
    file(GLOB _bb_data "${_bb_x}/data.tar.*")
    list(LENGTH _bb_data _bb_data_n)
    if (NOT _bb_data_n EQUAL 1)
        message(FATAL_ERROR "linux: ${_busybox_deb_name} is not a .deb (no data.tar.* inside)")
    endif ()
    file(ARCHIVE_EXTRACT INPUT "${_bb_data}" DESTINATION "${_bb_x}")
    if (NOT EXISTS "${_bb_x}/usr/bin/busybox")
        message(FATAL_ERROR "linux: ${_busybox_deb_name} does not contain usr/bin/busybox")
    endif ()
    file(COPY "${_bb_x}/usr/bin/busybox" "${_bb_x}/usr/share/doc/busybox-static/copyright"
            DESTINATION "${SOCETEER_ROOT}/shared/vendor/busybox")
    file(REMOVE_RECURSE "${_bb_x}")
    message(STATUS "linux: busybox ready at ${SOCT_BUSYBOX}")
endif ()
