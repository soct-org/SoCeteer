<p align="center">SoCeteer - A framework for designing and running RISC-V-based SoCs on FPGA and in Simulation, built on top of Chisel.<br/>From a Scala design to a Linux shell on your board.</p>

<p align="center">
  <a href="https://github.com/soct-org/SoCeteer/actions/workflows/on-pr.yml"><img src="https://github.com/soct-org/SoCeteer/actions/workflows/on-pr.yml/badge.svg?branch=main" alt="CI" /></a><a href="https://github.com/soct-org/SoCeteer/actions/workflows/on-tag.yml"><img src="https://github.com/soct-org/SoCeteer/actions/workflows/on-tag.yml/badge.svg?branch=main" alt="Release Workflow" /></a><a href="https://github.com/orgs/soct-org/packages/container/package/soceteer"><img src="https://img.shields.io/badge/GHCR-soceteer-blue?logo=docker" alt="GHCR Package" /></a>
</p>

> [!IMPORTANT]
> This project is in early development and is NOT ready for any serious use. We recommend using **SoCeteer** for experimentation and learning purposes only at this time.
> For a more stable experience, please use the tagged releases.

### Features

#### Generators

| | |
|----|----|
| **RocketChip** | The [reference RISC-V core generator](https://github.com/chipsalliance/rocket-chip): in-order cores with caches, MMU and supervisor support; the default config |
| **BOOM** | A [superscalar out-of-order RISC-V core](https://github.com/riscv-boom/riscv-boom), for when single-thread performance matters |
| **Shuttle** | A [superscalar in-order RISC-V core](https://github.com/ucb-bar/shuttle) - more throughput than RocketChip without going out-of-order |
| **Saturn** | A [RISC-V vector unit](https://github.com/ucb-bar/saturn-vectors) (RVV) that attaches to RocketChip and Shuttle cores |
| **Gemmini** | A [systolic-array ML accelerator](https://github.com/ucb-bar/gemmini), attached to a core as a RoCC coprocessor |
| **L2 cache** | [SiFive's inclusive last-level cache](https://github.com/sifive/block-inclusivecache-sifive), shared by all cores and added through a single config |
| **Nail** | A [fault-injection and reliability-evaluation framework](https://gitlab.iti.uni-luebeck.de/pubs/nail) (built on [Chiffre](https://github.com/IBM/chiffre)): bit flips and stuck-at faults injected into the running design, controlled from software. Not a submodule - clone it into `generators/` and the build picks it up |

#### System design

| | |
|----|----|
| **Configs** | Cores are picked, sized and combined through Chisel configs; both Chisel generations are supported (edu.berkeley.cs 3.6.1, org.chipsalliance 7.13.0) |
| **Block-design DSL** | Components, connections, clock domains and timing constraints written in Scala - every line of [Vivado](https://www.amd.com/en/products/software/adaptive-socs-and-fpgas/vivado.html) TCL is generated |
| **Memory** | `--ext-mem-part` names the DIMM you inserted; capacity, device tree and address decode follow - including modules the board's preset does not know |
| **Boards** | ZCU104, VCU118 - a new board is one Scala definition |

#### Running a design

| | |
|----|----|
| **Simulation** | The design runs under [Verilator](https://www.veripool.org/wiki/verilator): host-bridged syscalls, waveform tracing, and live GDB debugging of the simulated SoC |
| **FPGA builds** | The launcher drives Vivado from project generation to the finished bitstream, locally or on a remote build server |
| **Runs anywhere** | Docker images for x86_64 and ARM64; native on Linux, macOS and [Windows](https://htmlpreview.github.io/?https://github.com/soct-org/SoCeteer/blob/main/docs/guides/windows.html) |

#### Linux

| | |
|----|----|
| **Boot image** | One `BOOT.ELF` - firmware, kernel and BusyBox userspace - loaded from the SD card or over JTAG; device tree, memory map and console come from the design, so one kernel serves every design |
| **Shell image** | Boots into a BusyBox shell on the serial console and the monitor alike; `reboot` works |
| **Persistent storage** | `soct` keeps a persistent environment on the SD card or a USB stick - files and shell history survive reboots |
| **Drivers** | Out-of-tree kernel modules build with the rest in one CMake target and land in the boot image; an SD-card driver ships in-tree (`/dev/mmcblk0`) |
| **Toolchains** | CMake projects for boot ROMs and bare-metal programs; a separate LLVM/musl project for everything Linux - toolchains are fetched or auto-detected |

#### Display & peripherals

| | |
|----|----|
| **Display** | The Linux console on a DisplayPort monitor ([guide](https://htmlpreview.github.io/?https://github.com/soct-org/SoCeteer/blob/main/docs/guides/linux-monitor.html), [internals](https://htmlpreview.github.io/?https://github.com/soct-org/SoCeteer/blob/main/docs/systems/video.html)), with a display that CPU load cannot starve |
| **Video tools** | Runtime resolution switching (`fbmode`) and a framebuffer image viewer (`fbimg`) ship in the image |
| **USB** | Host controller on by default on MPSoC boards: keyboard plus monitor make the board a self-contained terminal, and USB sticks can carry the persistent environment |

### Get started

```bash
git clone --recurse-submodules https://github.com/soct-org/SoCeteer.git
# If already cloned without submodules: git submodule update --init --recursive
```

⚠️ Don't open the project in an IDE before initializing submodules.

Then follow [Setting up SoCeteer](https://htmlpreview.github.io/?https://github.com/soct-org/SoCeteer/blob/main/docs/guides/setup.html): host packages, the
IDE projects and a first generated design. Alternatively, the prebuilt Docker image
carries every host dependency ([Dockerfile](Dockerfile)):

```bash
docker run --rm -it -u $(id -u):$(id -g) -v "$PWD":/soceteer -w /soceteer ghcr.io/soct-org/soceteer:latest bash
```

### Documentation

**Guides** - step-by-step example runs:

* [Setting up SoCeteer](https://htmlpreview.github.io/?https://github.com/soct-org/SoCeteer/blob/main/docs/guides/setup.html) - from the clone to the IDE projects and a first generated design
* [Running a Design in Simulation](https://htmlpreview.github.io/?https://github.com/soct-org/SoCeteer/blob/main/docs/guides/simulation.html) - the design in Verilator: no board needed, console on the terminal, waveforms on demand
* [From Design to Bitstream](https://htmlpreview.github.io/?https://github.com/soct-org/SoCeteer/blob/main/docs/guides/bitstream.html) - generate a design and let the launcher drive Vivado to a bitstream
* [Remote Development](https://htmlpreview.github.io/?https://github.com/soct-org/SoCeteer/blob/main/docs/guides/remote.html) - build and flash through a server that has Vivado and the board
* [Monitor & Linux Programs](https://htmlpreview.github.io/?https://github.com/soct-org/SoCeteer/blob/main/docs/guides/linux-monitor.html) - the full run: Linux from SD, a DisplayPort console, persistent storage on the card

**Systems** - per-subsystem internals and reference: the hardware flow, the block-design
DSL, memory, the Linux boot chain, the video pipeline and more, plus the glossary and the
Scaladoc API reference. Everything is on **[the documentation site](https://htmlpreview.github.io/?https://github.com/soct-org/SoCeteer/blob/main/docs/docs.html)**
(rendered through htmlpreview) - or open `docs/docs.html` from the clone (`sbt buildDocs`
regenerates the API reference). All launcher options: `sbt "runMain soct.SOCTLauncher --help"`.
