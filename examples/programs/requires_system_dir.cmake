# Programs that require the path with the SoC output files (the system dir) emitted by SoCeteer can include this cmake

if (NOT DEFINED SYSTEM_DIR)
    message(WARNING "SYSTEM_DIR is not defined but program <${PROGRAM}> requires it; It will not compile! Please set it to the path where SoC output files are located, for example .../soceteer/workspace/RocketB1-64/sim")
else ()
    if (NOT EXISTS ${SYSTEM_DIR})
        message(FATAL_ERROR "SYSTEM_DIR <${SYSTEM_DIR}> does not exist although it is defined. Please remove it or set it to the path where SoC output files are located, for example .../soceteer/workspace/RocketB1-64/sim")
    else ()
        message(STATUS "Using SYSTEM_DIR: ${SYSTEM_DIR}")
    endif ()
endif ()