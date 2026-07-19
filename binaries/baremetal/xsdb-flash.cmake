# JTAG flash target for a bare-metal program - included by soctglue-static.cmake, so
# every bare-metal program gets `<name>-flash`. The machinery (wrapper generation, the
# a0/a1 boot-convention handoff, SOCT_FLASH_* variables, SOCT_FLASH_PRELUDE support)
# lives in shared/cmake/SoctXsdbFlash.cmake, shared with the linux project's boot-image
# flashing.

include(SoctXsdbFlash)

soct_xsdb_flash_target(${SOCT_PROGRAM} "$<TARGET_FILE:${SOCT_PROGRAM}>" ${SOCT_PROGRAM})

if (TARGET ${SOCT_PROGRAM}-flash)
    # CROSSCOMPILING_EMULATOR is used by ctest / cmake --build --target run.
    # Prepending python3 makes the wrapper work on all platforms.
    set_target_properties(${SOCT_PROGRAM} PROPERTIES
            CROSSCOMPILING_EMULATOR "python3;${CMAKE_CURRENT_BINARY_DIR}/${SOCT_PROGRAM}-flash.py")
endif ()
