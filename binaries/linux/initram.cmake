# Template for Linux userspace programs that are also a BOOTABLE IMAGE: everything
# userspace.cmake provides, plus a <name>-boot-elf target producing
# SOCT_ELFS_DIR/<name>.BOOT.ELF - a kernel with THIS program as the initramfs /init,
# wrapped in OpenSBI fw_payload, loadable by the sd-boot bootrom. That turns "run a
# userspace program on the hardware" into: build, copy to SD as BOOT.ELF, boot.
#
#   list(APPEND CMAKE_C_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.c)
#   include(${CMAKE_CURRENT_LIST_DIR}/../../initram.cmake)
#
# The kernel and OpenSBI build directories are shared between all boot images (only the
# initramfs packing and links re-run per image, serialized through a job pool); the
# program runs as PID 1 and must never exit - see programs/linux-init for the pattern.

include(${CMAKE_CURRENT_LIST_DIR}/userspace.cmake)

soct_linux_add_boot_image(${SOCT_PROGRAM})

# <name>-flash: JTAG-load the boot image - BOOT.ELF is a plain physical-address ELF, so
# this boots Linux (with this program as /init) without touching the SD card. The a1
# handoff carries the ROM DTB address as always; OpenSBI ignores it in favor of the
# embedded, fixed-up device tree. Only created when the SOCT_FLASH_* variables are set.
include(SoctXsdbFlash)
soct_xsdb_flash_target(${SOCT_PROGRAM} "${SOCT_ELFS_DIR}/${SOCT_PROGRAM}.BOOT.ELF" ${SOCT_PROGRAM}-boot-elf)
