# linux/rootfs — dynamically linked userspace (reserved)

The future home of the dynamic-linking world: a root-filesystem staging tree and a
`linux-dynamic` program template that links against a shared musl installed on the
target, instead of baking a static copy into every binary.

Prerequisites, in order:
1. an SD-controller kernel driver (DONE: `drivers/sdc` - Linux mounts the card's
   partitions as `/dev/mmcblk0*`),
2. BusyBox (the static build already boots from `userspace/shell`; a rootfs promotes it
   to the installed userland),
3. the sysroot's musl built `--enable-shared` (its dynamic linker is tiny and comes from
   the same build that already produces the static archives).

Until then, all userspace is static and lives in `userspace/` - delivered inside boot
images. The split exists now so programs never need to move again: static bring-up tools
stay in `userspace/`, the dynamic world grows here.
