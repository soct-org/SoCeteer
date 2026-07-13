<p align="center">SoCeteer - A framework for designing and running RISC-V-based SoCs on FPGA and in Simulation, built on top of Chisel.</p>

<p align="center">
  <a href="https://github.com/soct-org/SoCeteer/actions/workflows/on-pr.yml"><img src="https://github.com/soct-org/SoCeteer/actions/workflows/on-pr.yml/badge.svg?branch=main" alt="CI" /></a><a href="https://github.com/soct-org/SoCeteer/actions/workflows/on-tag.yml"><img src="https://github.com/soct-org/SoCeteer/actions/workflows/on-tag.yml/badge.svg?branch=main" alt="Release Workflow" /></a><a href="https://github.com/orgs/soct-org/packages/container/package/soceteer"><img src="https://img.shields.io/badge/GHCR-soceteer-blue?logo=docker" alt="GHCR Package" /></a>
</p>

> [!IMPORTANT]
> This project is in early development and is NOT ready for any serious use. We recommend using **SoCeteer** for experimentation and learning purposes only at this time.
> For a more stable experience, please use the tagged releases.

### Features

* Included generators contain: **[RocketChip](https://github.com/chipsalliance/rocket-chip)**,
 **[BOOM](https://github.com/riscv-boom/riscv-boom)**,
 **[Gemmini](https://github.com/ucb-bar/gemmini)** and more!
* Emit designs for Simulation using [Verilator](https://www.veripool.org/wiki/verilator) or
 FPGA synthesis using [Vivado](https://www.amd.com/en/products/software/adaptive-socs-and-fpgas/vivado.html)
* Built-in Vivado Block Design DSL: components, ports, connections, clock domains, timing constraints and TCL generation - all in Scala, without hand-writing TCL
* Custom DIMM support: select the inserted memory module with `--ext-mem-part`; capacities, device tree and address decode follow automatically
* Support for edu.berkeley.cs.chisel (3.6.1) and org.chipsalliance.chisel (7.11.0)
* CMake projects for building bootroms and binaries for the generated designs (simulation and FPGA)
* Docker images for x86_64 and ARM64 hosts; runs natively on Linux, macOS and Windows

### Documentation

This README is only the quick start. The full documentation is local to the repository -
open **[docs/docs.html](docs/docs.html)** in a browser for the guides (architecture & hardware flow,
bare-metal programs & soctglue, FPGA memory & custom DDR4) and the API reference
(regenerate with `sbt buildDocs`). All launcher options: `sbt "runMain soct.SOCTLauncher --help"`.

---

## Setup

Clone with submodules and install the system dependencies (full reference: [Dockerfile](Dockerfile)):

```bash
git clone --recurse-submodules https://github.com/soct-org/SoCeteer.git
# If already cloned without submodules: git submodule update --init --recursive
```

⚠️ Don't open the project in an IDE before initializing submodules.

* **Java 11+ & [SBT](https://www.scala-sbt.org/1.x/docs/Setup.html)** (or IntelliJ IDEA with the Scala plugin)
* **CMake & Ninja**, **Device Tree Compiler (dtc)**, **Flex & Bison**:

```bash
# Ubuntu/Debian:  sudo apt-get install cmake ninja-build device-tree-compiler flex bison
# Arch Linux:     sudo pacman -S cmake ninja dtc flex bison
# macOS:          brew install cmake ninja dtc flex bison
# Windows:        choco install cmake ninja dtc-msys2 winflexbison3
```

The RISC-V toolchain ([xpack-dev-tools](https://github.com/xpack-dev-tools/riscv-none-elf-gcc-xpack)) and
[Verilator](shared/verilator) are downloaded/built automatically on first use.

---

## Quick Start: Simulation

```bash
# 1. Emit the design (default config, Verilator target)
sbt "runMain soct.SOCTLauncher"

# Or via Docker:
# docker run --rm -it -u $(id -u):$(id -g) -v "$PWD":/soceteer -w /soceteer ghcr.io/soct-org/soceteer:latest bash

# 2. Build the example binary
mkdir -p workspace/RocketB1-64/sim/build/prog-build
cmake -S binaries -B workspace/RocketB1-64/sim/build/prog-build -DSOCT_SYSTEM=/path/to/workspace/RocketB1-64/sim/SOCTSystem.cmake
cmake --build workspace/RocketB1-64/sim/build/prog-build --target hello-hart

# 3. Build and run the Verilator simulator
mkdir -p workspace/RocketB1-64/sim/build/sim-build
cmake -S sim -B workspace/RocketB1-64/sim/build/sim-build -DCMAKE_BUILD_TYPE=Release -DSOCT_SYSTEM=/path/to/workspace/RocketB1-64/sim/SOCTSystem.cmake
cmake --build workspace/RocketB1-64/sim/build/sim-build
workspace/RocketB1-64/sim/build/sim-build/simulator workspace/RocketB1-64/sim/elfs/hello-hart.elf
```

Every emit writes `workspace/RocketB1-64/sim/SOCTSystem.cmake` (variables for arch, core count, ABI, paths) and updates the
`SOCTSystem-latest.cmake` symlink at the project root, which all CMake projects fall back to when no
explicit `SOCT_SYSTEM` is passed. Pick a different system with
`--config <class>` (default: `soct.RocketB1`) and `--xlen 32/64`.

**IntelliJ IDEA / CLion:** run the `main` method in [./src/main/scala/soct/SOCTLauncher.scala](./src/main/scala/soct/SOCTLauncher.scala), then open
`binaries` and `sim` as CLion projects with `-DSOCT_SYSTEM=/path/to/workspace/RocketB1-64/sim/SOCTSystem.cmake` in the CMake options.

---

## Quick Start: FPGA (ZCU104)

```bash
# Emit the design, generate the Vivado project, block design and constraints:
sbt "runMain soct.SOCTLauncher --target vivado --board ZCU104 --vivado /path/to/vivado"
```

Open the generated project (`workspace/<config>/ZCU104/vivado-project`), run synthesis and
implementation, and program the bitstream. Programs are then loaded over JTAG - see the
[Bare-Metal Programs guide](docs/guides/binaries.html) for the `<program>-flash` targets.

**Using the DIMM that is actually inserted:** Vivado's board flow locks the DDR4 controller to the
board-preset module (ZCU104 preset: 4 GiB). If your board carries a different DIMM, pass its
Vivado part name - the design switches to a custom DDR4 interface and sizes memory, device tree and
address decode from the part:

```bash
# Example: 16 GiB dual-rank SODIMM in the ZCU104 slot
sbt "runMain soct.SOCTLauncher --target vivado --board ZCU104 --ext-mem-part MTA16ATF2G64HZ-2G3 --vivado /path/to/vivado"
```

Details (part registry, custom interface internals, on-hardware validation with `mem-test`):
[FPGA Memory & Custom DDR4](docs/guides/fpga-memory.html). Supported boards:
ZCU104, VCU118 - add new boards by extending `FPGA` and registering them in `FPGARegistry`.

---

## Hints
* The [firtool](https://github.com/llvm/circt/releases) binary needed for Chisel is x86_64-only; ARM64 macOS needs Rosetta (`softwareupdate --install-rosetta --agree-to-license`).
* If UART to the board fails, close the Vivado hardware manager; if `/dev/ttyUSB*` disappears, `udevadm trigger` can help. Avoid USB hubs for the board connection.
* On Windows, Verilator requires Visual Studio and building the simulator requires MinGW. For command-length errors during Verilator builds, move the project to a shorter path or pass `--single-verilog-file`.
