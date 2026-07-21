# linux/drivers — out-of-tree kernel modules for SoCeteer IP

Drivers for hardware that mainline Linux does not know: each subdirectory with a `Kbuild`
file is auto-discovered and built as an external kbuild module (`<name>-driver` target)
against the project's shared kernel build - always with matching vermagic, since it is
the same tree and toolchain. Boot images pack the resulting `.ko` into their initramfs
and load it from `/init` (see `userspace/shell/init.sh`); the kernel's initramfs rules
track the packed files, so a rebuilt module re-packs automatically.

This is deliberately NOT the same bucket as `../patches/`: patches are minimal fixes to
mainline drivers that upstream would plausibly accept; SoC- and vendor-specific drivers
live here as modules and never touch the kernel tree.

Current modules:
- `sdc/` — the AXI SD-card controller (`riscv,axi-sd-card-1.0`), from
  eugene-tarassov/vivado-risc-v, which is also where the controller's RTL comes from
  (vendored under `src/main/resources/sdc`). Gives Linux the SD card as `/dev/mmcblk0`;
  the shell image loads it at boot, after which the card's partitions mount normally
  (`mount -t vfat /dev/mmcblk0p1 /mnt`). The bare-metal reference for the register map
  is `bootroms/sd-boot/bootrom.c`. Note there is no concurrent-access hazard with the
  boot ROM: it read BOOT.ELF from the card long before Linux started.
