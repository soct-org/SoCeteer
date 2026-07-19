# host-compat

Headers the Linux kernel's *host* tools need but some build hosts lack. Injected via
`HOSTCFLAGS=-I<this dir>` by `boot/CMakeLists.txt` on hosts that need it (macOS today:
it ships no `<elf.h>`, which `scripts/elf-parse.h` and friends include). The kernel's
*target* compilation never sees this directory.

- `elf.h` — verbatim from musl libc (https://git.musl-libc.org, `include/elf.h`),
  MIT-licensed. musl's is the canonical self-contained `elf.h` used for exactly this
  purpose; do not edit it here, refresh from upstream instead.
