# shell - an interactive BusyBox userspace boot image.
# The initramfs packs the prebuilt static busybox (fetched at configure - see
# cmake/busybox.cmake) together with init.sh, which mounts the pseudo filesystems,
# installs the applet links and keeps a job-controlled shell alive on the console -
# plus the repo's own userspace tools for what busybox lacks (fbmode: Debian's
# busybox-static carries no fbset applet).
set(INIT_SH "${CMAKE_CURRENT_SOURCE_DIR}/init.sh")
set(PROFILE_SH "${CMAKE_CURRENT_SOURCE_DIR}/profile.sh")
set(SOCT_SH "${CMAKE_CURRENT_SOURCE_DIR}/soct.sh")
set(QUIRKS_SH "${CMAKE_CURRENT_SOURCE_DIR}/quirks.sh")

# /etc/soct-release: version plus build identity, regenerated at BUILD time so
# the running system can always say which build it is (see gen-release.cmake).
set(SOCT_RELEASE "${CMAKE_CURRENT_BINARY_DIR}/soct-release")
add_custom_target(soct-release-gen
        COMMAND ${CMAKE_COMMAND} -DSOCETEER_ROOT=${SOCETEER_ROOT} -DOUT=${SOCT_RELEASE}
                -P ${CMAKE_CURRENT_SOURCE_DIR}/gen-release.cmake
        BYPRODUCTS "${SOCT_RELEASE}"
        COMMENT "Generating soct-release (build identity)"
        VERBATIM)
set(SDC_KO "${CMAKE_BINARY_DIR}/modules/sdc/fpga-axi-sdc.ko")
set(DP_KO "${CMAKE_BINARY_DIR}/modules/dp/soct-dp.ko")
set(FBMODE_BIN "${CMAKE_BINARY_DIR}/userspace/fbmode/fbmode")
set(FBIMG_BIN "${CMAKE_BINARY_DIR}/userspace/fbimg/fbimg")
set(DOOM_BIN "${CMAKE_BINARY_DIR}/userspace/doom/doom")
configure_file(initramfs.desc.in "${CMAKE_CURRENT_BINARY_DIR}/shell-initramfs.desc" @ONLY)

soct_linux_add_boot_image(shell
        DESC "${CMAKE_CURRENT_BINARY_DIR}/shell-initramfs.desc"
        DEPENDS "${SOCT_BUSYBOX}" "${INIT_SH}" "${PROFILE_SH}" "${SOCT_SH}" "${QUIRKS_SH}")
# The initramfs packs the SD-controller module; the kernel's initramfs rules track the
# packed files themselves, so the always-run chain just needs the build order.
add_dependencies(shell-boot-elf sdc-driver dp-driver fbmode fbimg doom soct-release-gen)

# shell-flash: JTAG-boot straight into the shell, no SD card involved (see initram.cmake
# for how the flash handoff works). Only created when the SOCT_FLASH_* variables are set.
# The PS is initialized first, for the reasons initram.cmake gives.
include(SoctPsuInit)
include(SoctXsdbFlash)
soct_xsdb_flash_target(shell "${SOCT_ELFS_DIR}/shell.BOOT.ELF" shell-boot-elf)
