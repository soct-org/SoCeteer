# linux/drivers — kernel modules for SoCeteer IP (reserved)

Out-of-tree Linux kernel modules for the SoC's peripherals, built with kbuild
(`make M=<dir>`) against the shared kernel build directory this project maintains -
keeping driver work out of the drop-in `linux-stable/` checkout so it survives kernel
updates.

Planned first occupant: a driver for the OpenCores SD Card Controller
(`riscv,axi-sd-card-1.0`), which has no mainline driver. It unlocks storage under Linux -
mounting the SD card at runtime, and with it the `rootfs/` world (dynamically linked
userspace on a real root filesystem instead of an initramfs). The bare-metal reference
for every register quirk is `bootroms/sd-boot/bootrom.c`.

Module delivery until then follows the same path as everything else: packed into a boot
image's initramfs (`CONFIG_MODULES=y` plus the `.ko` in the archive, loaded by `/init`).
