# binaries — what runs where

Every directory here is named after the **execution context** of the binaries it holds.
Two independent CMake projects live side by side, because CMake allows one toolchain per
build tree and the two worlds need different ones.

| Directory | Execution context | Toolchain / runtime | Delivered by |
|---|---|---|---|
| `bootroms/<name>/` | On-chip ROM, at reset (first stage) | vendored bare-metal GCC, self-contained (no soctglue) | baked into the bitstream |
| `baremetal/<name>/` | Directly on the SoC, no OS (M-mode) | vendored bare-metal GCC + soctglue/newlib | `sd-boot` from SD, or JTAG (`<name>-flash`) |
| `linux/userspace/<name>/` | Linux userspace, PID 1 or initramfs tool | LLVM/Clang + static musl (own project) | inside a boot image (`<name>.BOOT.ELF`), flashable |
| `linux/drivers/<name>/` | Linux kernel (out-of-tree modules) | kernel kbuild against the shared build dir | `.ko` inside a boot image's initramfs, loaded by `/init` |
| `linux/rootfs/` | Linux userspace, dynamically linked — *reserved* | LLVM/Clang + shared musl | see its README |

- **`binaries/` (this directory) is the bare-metal CMake project** — configure with the
  default toolchain and a `SOCT_SYSTEM` file; boot ROMs and bare-metal programs build here.
  Templates: `baremetal/soctglue-static.cmake`, `bootroms/template.cmake`.
- **`binaries/linux/` is a separate CMake project** for everything involving the kernel:
  the musl sysroot, userspace programs (`userspace/userspace.cmake`, and
  `userspace/initram.cmake` for programs that become bootable images), the kernel and
  OpenSBI builds. The kernel/OpenSBI source trees are drop-in checkouts at
  `linux/linux-stable/` and `linux/opensbi/`.

The full story (templates, host requirements, boot chain) is in the local docs:
`docs/guides/binaries.html`.
