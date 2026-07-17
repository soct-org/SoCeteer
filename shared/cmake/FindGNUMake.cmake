# FindGNUMake
# -----------
# Finds GNU make, honoring a minimum version:
#
#   find_package(GNUMake 4.0)
#
# Result variables:
#   GNUMake_FOUND       - a GNU make satisfying the requested version exists
#   GNUMAKE_EXECUTABLE  - its path
#   GNUMAKE_VERSION     - its version
#
# Plain `find_program(... make)` is not good enough here: on macOS/BSD `make` may be BSD
# make or an ancient GNU make (Apple ships 3.81), while a current GNU make sits nearby
# as `gmake` (Homebrew/MacPorts) - possibly keg-only and off PATH. So this module
# collects every candidate from PATH *and* the well-known per-platform locations,
# version-checks each (`--version` must identify GNU Make), and picks the first one
# that satisfies the requested minimum. `gmake`/`gnumake` are preferred over `make`.

set(_gnumake_names gmake gnumake make mingw32-make)
set(_gnumake_paths
        /opt/homebrew/bin /opt/homebrew/opt/make/bin # Homebrew, Apple silicon (opt/ = version-stable keg alias, present even when unlinked)
        /usr/local/bin /usr/local/opt/make/bin       # Homebrew, Intel macs
        /opt/local/bin                               # MacPorts
)
if (DEFINED ENV{HOMEBREW_PREFIX})
    list(PREPEND _gnumake_paths "$ENV{HOMEBREW_PREFIX}/bin" "$ENV{HOMEBREW_PREFIX}/opt/make/bin")
endif ()

# Candidates from PATH (+ fixed paths), name-major order so gmake anywhere beats make.
set(_gnumake_candidates "")
foreach (_name IN LISTS _gnumake_names)
    find_program(_GNUMAKE_CANDIDATE_${_name} NAMES ${_name} PATHS ${_gnumake_paths})
    mark_as_advanced(_GNUMAKE_CANDIDATE_${_name})
    if (_GNUMAKE_CANDIDATE_${_name})
        file(REAL_PATH "${_GNUMAKE_CANDIDATE_${_name}}" _gnumake_real)
        list(APPEND _gnumake_candidates "${_gnumake_real}")
    endif ()
endforeach ()
# The fixed locations again, explicitly: find_program stops at the first (PATH) hit per
# name, which would let an old `make` on PATH shadow a newer one in a fixed location.
foreach (_dir IN LISTS _gnumake_paths)
    foreach (_name IN LISTS _gnumake_names)
        if (EXISTS "${_dir}/${_name}")
            file(REAL_PATH "${_dir}/${_name}" _gnumake_real)
            list(APPEND _gnumake_candidates "${_gnumake_real}")
        endif ()
    endforeach ()
endforeach ()
list(REMOVE_DUPLICATES _gnumake_candidates)

set(GNUMAKE_EXECUTABLE "")
set(GNUMAKE_VERSION "")
foreach (_cand IN LISTS _gnumake_candidates)
    execute_process(COMMAND "${_cand}" --version
            RESULT_VARIABLE _gnumake_res OUTPUT_VARIABLE _gnumake_out
            ERROR_QUIET OUTPUT_STRIP_TRAILING_WHITESPACE)
    if (_gnumake_res EQUAL 0 AND _gnumake_out MATCHES "GNU Make ([0-9]+(\\.[0-9]+)*)")
        set(_gnumake_ver "${CMAKE_MATCH_1}")
        if (NOT GNUMake_FIND_VERSION OR NOT _gnumake_ver VERSION_LESS GNUMake_FIND_VERSION)
            set(GNUMAKE_EXECUTABLE "${_cand}")
            set(GNUMAKE_VERSION "${_gnumake_ver}")
            break ()
        endif ()
    endif ()
endforeach ()

include(FindPackageHandleStandardArgs)
find_package_handle_standard_args(GNUMake
        REQUIRED_VARS GNUMAKE_EXECUTABLE
        VERSION_VAR GNUMAKE_VERSION)
