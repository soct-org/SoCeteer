# shell - an interactive BusyBox userspace, and the default boot image (SOCT_BOOT_PROGRAM).
# Nothing is compiled here: the initramfs packs the prebuilt static busybox (fetched at
# configure - see the BusyBox section of the project CMakeLists) together with init.sh,
# which mounts the pseudo filesystems, installs the applet links and keeps a
# job-controlled shell alive on the console.
set(INIT_SH "${CMAKE_CURRENT_SOURCE_DIR}/init.sh")
configure_file(initramfs.desc.in "${CMAKE_CURRENT_BINARY_DIR}/shell-initramfs.desc" @ONLY)

soct_linux_add_boot_image(shell
        DESC "${CMAKE_CURRENT_BINARY_DIR}/shell-initramfs.desc"
        DEPENDS "${SOCT_BUSYBOX}" "${INIT_SH}")

# shell-flash: JTAG-boot straight into the shell, no SD card involved (see initram.cmake
# for how the flash handoff works). Only created when the SOCT_FLASH_* variables are set.
include(SoctXsdbFlash)
soct_xsdb_flash_target(shell "${SOCT_ELFS_DIR}/shell.BOOT.ELF" shell-boot-elf)
