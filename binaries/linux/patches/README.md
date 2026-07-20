# linux/patches — kernel patches the boot depends on

Each `*.patch` here is applied to the `linux-stable` tree by the project configure
(idempotently: applied patches are detected and skipped; a tree the patch no longer fits
fails the configure loudly). The kernel tree therefore shows as modified — that is
expected and intentional.

Policy: patches must be **minimal and upstream-candidates** — fixes to mainline drivers
that mainline would plausibly accept, each with its rationale in the patch header. SoC-
or vendor-specific drivers do not belong here; they go to `../drivers/` as out-of-tree
modules.

Current patches:
- `0001-irqchip-xilinx-platform-probe.patch` — the AXI INTC driver only registers as an
  early irqchip, but its interrupt parent (the RISC-V PLIC) probes as a platform device,
  so `of_irq_init()` abandons the INTC and every fabric interrupt (console included)
  defers forever. Adds the same dual platform-driver registration the PLIC itself has.
