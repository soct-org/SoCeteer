# Build-time generation of /etc/soct-release: the version from VERSION plus a
# build identity (git state + UTC time), so the running system can always say
# which build it is - the banner prints it, and `soct` compares it against the
# copy inside a persistent environment to flag stale tools.
# Runs at BUILD time (custom target), not configure time: a configure-time
# stamp goes stale the moment code rebuilds without a reconfigure.
file(READ "${SOCETEER_ROOT}/VERSION" _v)
string(STRIP "${_v}" _v)
set(_sha "unknown")
find_package(Git QUIET)
if (GIT_FOUND)
    execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${SOCETEER_ROOT}" describe --always --dirty
            OUTPUT_VARIABLE _sha OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
endif ()
string(TIMESTAMP _t "%Y-%m-%d %H:%MZ" UTC)
file(WRITE "${OUT}" "SOCT_VERSION=${_v}\nSOCT_BUILD='${_sha} ${_t}'\n")
