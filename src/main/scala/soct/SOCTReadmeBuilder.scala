package soct

// Import the generated BuildInfo as info (short alias for convenience):

import soct.SOCTLauncher.SOCTConfig
import soct.SOCTNames.{DEFAULT_EXAMPLE_BINARY, SOCT_SIMULATOR_EXE, SOCT_SYSTEM_CMAKE_FILE}
import soct.build.{BuildInfo => info}
import soct.system.vivado.fpga.FPGARegistry

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * This object is responsible for building the README file for the SOCT project.
 * It will gather information from the various subprojects and generate a comprehensive README.
 */
object SOCTReadmeBuilder {
  val name = soct.build.BuildInfo.name
  val sct = s"**$name**"
  val url = "https://github.com/soct-org/SoCeteer"
  val gitUrl = url + ".git"
  val root = "$PWD"
  val rootDocker = "/soceteer"

  val chiselVersions = info.supportedChiselVersions.split(",").map(_.trim).toList
  val chisel3s = chiselVersions.filter(_.startsWith("3"))
  val otherChisels = chiselVersions.filterNot(_.startsWith("3"))

  val sl = SOCTLauncher.getClass.getSimpleName.stripSuffix("$")
  val slPath = SOCTLauncher.getClass.getCanonicalName.stripSuffix("$")
  val slFilePath = info.scalaMain + "/" + SOCTLauncher.getClass.getCanonicalName.stripSuffix("$").replace(".", "/") + ".scala"

  val defaultArgs = SOCTArgs()
  val defaultConfigPath = defaultArgs.baseConfig.getClass.getCanonicalName
  val defaultConfig = SOCTUtils.configName(defaultArgs.baseConfig, defaultArgs.xlen)


  val paths = new SimSOCTPaths(defaultArgs, SOCTConfig(defaultArgs))

  val exampleOutDir = rel(paths.systemDir)
  val soctCmakePath = s"$exampleOutDir/${SOCT_SYSTEM_CMAKE_FILE}"

  val defaultBin = DEFAULT_EXAMPLE_BINARY
  val defaultBinPath = rel(paths.elfsDir.resolve(s"$defaultBin.elf"))

  val simBuildDir = rel(paths.buildDir.resolve("sim-build"))
  val progBuildDir = rel(paths.buildDir.resolve("prog-build"))
  val cmakeSoctSystemDef = s"-DSOCT_SYSTEM=${rel(paths.soctSystemCMakeFile)}"


  def rel(path: Path): String = {
    SOCTPaths.projectRoot.relativize(path).toString
  }

  def path(s: String): String = {
    rel(SOCTPaths.get(s))
  }

  def emit(): String = {
    s"""<p align="center">SoCeteer - A framework for designing and running RISC-V-based SoCs on FPGA and in Simulation, built on top of Chisel.</p>
       |
       |<p align="center">
       |  <a href="https://github.com/soct-org/SoCeteer/actions/workflows/on-pr.yml"><img src="https://github.com/soct-org/SoCeteer/actions/workflows/on-pr.yml/badge.svg?branch=main" alt="CI" /></a><a href="https://github.com/soct-org/SoCeteer/actions/workflows/on-tag.yml"><img src="https://github.com/soct-org/SoCeteer/actions/workflows/on-tag.yml/badge.svg?branch=main" alt="Release Workflow" /></a><a href="https://github.com/orgs/soct-org/packages/container/package/soceteer"><img src="https://img.shields.io/badge/GHCR-soceteer-blue?logo=docker" alt="GHCR Package" /></a>
       |</p>
       |
       |> [!IMPORTANT]
       |> This project is in early development and is NOT ready for any serious use. We recomment using $sct for experimentation and learning purposes only at this time.
       |> For a more stable experience, please use the tagged releases.
       |
       |
       |> [!NOTE]
       |> Please take a look at [Known Issues](#known-issues-hints-and-limitations) for a list of current limitations and issues.
       |> Feel free to open issues and contribute to the project if you find any problems or have ideas for improvements!
       |
       |### Features
       |
       |* Included generators contain: **[RocketChip](https://github.com/chipsalliance/rocket-chip)**,
       | **[BOOM](https://github.com/riscv-boom/riscv-boom)**,
       | **[Gemmini](https://github.com/ucb-bar/gemmini)** and more!
       |
       |* Emit designs for Simulation using [Verilator](https://www.veripool.org/wiki/verilator),
       | FPGA synthesis using [Vivado](https://www.amd.com/en/products/software/adaptive-socs-and-fpgas/vivado.html)
       | and [Yosys](https://github.com/YosysHQ/yosys)-based flows for open-source FPGA toolchains (in development)
       |
       |* Built-in Vivado Block Design DSL: Describe Vivado IP block designs directly in Scala - components, ports, connections, clock domains, timing constraints and TCL generation, all without hand-writing TCL
       |
       |* Support for edu.berkeley.cs.chisel (${chisel3s.mkString(", ")}) and org.chipsalliance.chisel (${otherChisels.mkString(", ")})
       |
       |* CMake projects for building bootroms and binaries to run on the generated designs (both in simulation and on FPGA)
       |
       |* Very fast design generation (several times faster than other SoC builders)
       |
       |* Docker images available for easy setup and use, with support for both x86_64 and ARM64 hosts (including Apple Silicon)
       |
       |* Runs natively on Linux, macOS(ARM / x86_64), and Windows
       |
       |---
       |
       |## Setup and Dependencies
       |
       |The recommended way to use $sct is via IntelliJ IDEA with the Scala plugin, which provides excellent support for sbt projects.
       |However, you can also use it via the command line (CLI) with sbt (either in a docker container or natively).
       |
       |
       |* Clone the repository with recursive submodules to get the included generators and other dependencies.
       |```bash
       |# Clone the main branch with submodules:
       |git clone --recurse-submodules $gitUrl
       |
       |# Clone the latest release with submodules:
       |git clone --recurse-submodules --branch v${info.version} $gitUrl
       |
       |# If already cloned without --recurse-submodules:
       |git submodule update --init --recursive
       |```
       |
       |⚠️ Don't open the project in an IDE before initializing submodules, as it may add directories for uninitialized
       |submodules which can cause issues with cloning.
       |
       |
       |Next, ensure you have the necessary tools installed. For a full setup refer to the [Dockerfile](${path("dockerfile")}).
       |
       |---
       |
       |#### Java (11+) & SBT
       |
       |* **IntelliJ IDEA:** Install the Scala plugin and set a JDK 11+ (`File -> Project Structure -> SDK`).
       |* **CLI:** Install Java 11+ and [SBT](https://www.scala-sbt.org/1.x/docs/Setup.html). Ensure `java` and `sbt` are in your system `PATH`.
       |
       |---
       |
       |#### System Dependencies
       |
       |The following tools are required for building bootroms/binaries, compiling device trees, and compiling Verilator for simulation:
       |* **CMake & Ninja**: For building C/C++ projects.
       |* **Device Tree Compiler (dtc)**: For compiling `.dts` to `.dtb`.
       |* **Flex & Bison**: Required by Verilator.
       |
       |```bash
       |# Ubuntu/Debian
       |sudo apt-get install cmake ninja-build device-tree-compiler flex bison
       |
       |# Arch Linux
       |sudo pacman -S cmake ninja dtc flex bison
       |
       |# macOS
       |brew install cmake ninja dtc flex bison
       |
       |# Windows (via Chocolatey)
       |choco install cmake ninja dtc-msys2 winflexbison3
       |```
       |
       |---
       |
       |#### Auto-Installed Tooling
       |
       |The following tools are automatically downloaded or built when running $sct:
       |* **RISC-V Toolchain [(xpack-dev-tools)](https://github.com/xpack-dev-tools/riscv-none-elf-gcc-xpack)**: For compiling RISC-V programs and bootroms.
       |* **Verilator** (for Simulation): A maintained [submodule](${path("verilator")}) is built automatically during the first simulation run. (Windows requires Visual Studio 2022 and MinGW).
       |
       |---
       |
       |## Quick Start (Simulation)
       |
       |Running $sct with `--target verilator` elaborates the Chisel design and emits Verilog into the output directory.
       |The CMake project in [${path("sim")}](${path("sim")}) then verilates that Verilog and compiles a C++ harness
       |(using `main.cpp`, `dpi-c.cpp`, FESVR, and the RISC-V ISA disassembler) into a native simulator binary.
       |
       |The simulator loads a RISC-V ELF, communicates with the program's syscalls via **FESVR** (Front-End Server)
       |over the Debug Transport Module (DTM) using DPI-C, and exits when the program calls `tohost`.
       |A remote JTAG bitbang interface (port 1337) is also exposed for live debugging with OpenOCD/GDB.
       |
       |$sct emits a `$SOCT_SYSTEM_CMAKE_FILE` file at `$soctCmakePath` alongside the Verilog.
       |This file contains CMake variables (architecture, core count, ABI, source paths, etc.) consumed by both the
       |simulator CMake project and the binaries CMake project - so neither needs to be reconfigured between designs.
       |
       |#### CLI usage
       |```bash
       |# Emit the simulation design:
       |sbt "runMain $slPath --target verilator [options]"
       |```
       |
       |Key simulation options (pass `--help` for a full list):
       |
       || Option | Description |
       ||-|-|
       || `--config <class>` | Generator config class (default: `$defaultConfigPath`). |
       || `--xlen 32/64` | RISC-V word width (default: 64). |
       || `--core-freq-mhz <f>` | Sets all bus clock frequencies in MHz (default: 100). |
       || `--periphery-freq-mhz <f>` | Periphery bus frequency in MHz (default: 100). |
       || `--out-dir <path>` | Output directory (default: workspace subdirectory). |
       |
       |#### Simulator CMake options
       |
       |The simulator CMake project ([${path("sim")}](${path("sim")})) accepts the following CMake variables:
       |
       || Variable | Description |
       ||-|-|
       || `SOCT_SYSTEM` | **(Required)** Path to the emitted `$SOCT_SYSTEM_CMAKE_FILE` file. |
       || `VL_THREADS` | Number of Verilator threads (default: `SOCT_NCPUS + 1`). |
       || `VL_TRACE` | Enable VCD waveform tracing (off by default). |
       || `VL_TRACE_DEPTH` | Hierarchy depth limit for tracing (default: 2). |
       || `PASS_UNKNOWN_SYSCALLS` | Forward unknown syscalls to the host OS. |
       || `ENABLE_TRACE` | Enable `TRACE`-level simulator log output. |
       || `FORCE_ASSERTS` | Enable assertions in release builds. |
       |
       |#### Runtime simulator options
       |
       |```bash
       |# Run the simulator (pass ELF as first positional argument):
       |$simBuildDir/$SOCT_SIMULATOR_EXE <elf> [options]
       |```
       |
       || Option | Description |
       ||-|-|
       || `<elf>` | RISC-V ELF binary to run. |
       || `--reset-cycles=<n>` | Number of reset cycles before releasing reset (default: 100). |
       || `--log-level=<level>` | Log level: `trace`, `debug`, `info`, `warn`, `error`. |
       || `--log-file=<path>` | Write simulator logs to a file. |
       || `--all2console` | Mirror all log output to the console regardless of level. |
       || `--vcd-file=<path>` | VCD output path when `VL_TRACE` is enabled (default: `dump.vcd`). |
       |
       |#### Build and run end-to-end
       |
       |**IntelliJ IDEA / CLion:** Run the `main` method in [$slFilePath]($slFilePath) to emit the design, then open
       |`${path("binaries")}` and `${path("sim")}` as separate CLion projects and add `$cmakeSoctSystemDef` to CMake options.
       |
       |**CLI:**
       |```bash
       |# 1. Emit the design
       |sbt "runMain $slPath"
       |
       |# Or via Docker:
       |# docker run --rm -it -u $$(id -u):$$(id -g) -v "$root":$rootDocker -w $rootDocker ghcr.io/soct-org/soceteer:latest bash
       |
       |# 2. Build the example binary
       |mkdir -p $progBuildDir
       |cmake -S ${path("binaries")} -B $progBuildDir $cmakeSoctSystemDef
       |cmake --build $progBuildDir --target $defaultBin
       |
       |# 3. Build and run the Verilator simulator
       |mkdir -p $simBuildDir
       |cmake -S ${path("sim")} -B $simBuildDir -DCMAKE_BUILD_TYPE=Release $cmakeSoctSystemDef
       |cmake --build $simBuildDir
       |$simBuildDir/$SOCT_SIMULATOR_EXE $defaultBinPath
       |```
       |
       |#### What gets generated
       |
       |After running the launcher with `--target verilator`, the output directory contains:
       |* Verilog source files (`*.v` / `*.sv`) for the full SoC
       |* `$SOCT_SYSTEM_CMAKE_FILE` - CMake variables for arch, core count, paths, ABI, etc.
       |* FIRRTL intermediate files (low FIRRTL + optimised Verilog)
       |* Device tree source (`.dts`) and blob (`.dtb`)
       |* Register map files
       |
       |The [Simulation Tests](src/test/scala/soct/tests/SimulationSpec.scala) show all supported configurations.
       |The [GitHub Workflow](.github/workflows/test-simulation-on-push-native.yml) shows the full native setup for Windows, Linux and macOS.
       |
       |---
       |
       |## FPGA Deployment
       |
       |$sct targets Xilinx FPGAs via Vivado. It emits Verilog, a Vivado block design (TCL), timing constraints (XDC), and
       |optionally runs Vivado to generate the project automatically.
       |
       |* **Vivado:** Required for synthesis. Download from [here](https://www.xilinx.com/support/download/index.html/content/xilinx/en/downloadNav/vivado-design-tools.html).
       |* **Supported boards:** ${FPGARegistry.getKnownBoards.mkString(", ")} - add new boards by extending `FPGA` and registering in `FPGARegistry`.
       |
       |#### CLI usage
       |```bash
       |# Emit a design for the ZCU104 board and (optionally) run Vivado to create the project:
       |sbt "runMain $slPath --target vivado --board ZCU104 --vivado /path/to/vivado [options]"
       |```
       |
       |Key options for Vivado synthesis:
       |
       || Option | Description |
       ||-|-|
       || `--board <name>` | Target FPGA board (required). Available: ${FPGARegistry.getKnownBoards.mkString(", ")} |
       || `--vivado <path>` | Path to the `vivado` executable. If omitted, only the TCL/Verilog files are emitted. |
       || `--core-freq-mhz <f>` | Core/bus frequency in MHz (default: 100). |
       || `--periphery-freq-mhz <f>` | Periphery bus frequency in MHz (default: 100). |
       || `--no-override-vivado-project` | Do not overwrite an existing Vivado project in the output directory. |
       || `--remote-dir <path>` | Sync design files and run Vivado on a remote machine (requires `--ssh-config`). |
       || `--ssh-config <name>` | [OpenSSH](https://linux.die.net/man/5/ssh_config) config entry to use for remote Vivado. |
       || `--use-remote-vivado` | Treat the `--vivado` path as a path on the remote machine. |
       |
       |#### What gets generated
       |
       |After running, the output directory contains:
       |* Verilog source files (top-level `.v` + supporting modules)
       |* `$SOCT_SYSTEM_CMAKE_FILE` - CMake variables for arch, core count, paths, ABI, etc. (same as simulation)
       |* `init.tcl` - Vivado project initialisation script
       |* `bd.tcl` - Block design TCL (AXI interconnect, clocks, DDR4, UART, JTAG, etc.)
       |* `synth.tcl` - Synthesis run script
       |* `timing.tcl` - Timing constraints
       |* `xdc/` - Per-component XDC constraint files
       |* Device tree source (`.dts`) and blob (`.dtb`)
       |* Register map files
       |
       |### Vivado Block Design DSL
       |
       |$sct includes a Scala-embedded DSL for describing Vivado IP block designs (`soct.system.vivado`). Rather than
       |hand-writing TCL scripts, you compose a design programmatically:
       |
       |* **Components** (`soct.system.vivado.components`) - pre-built wrappers for common Xilinx IPs: AXI SmartConnect,
       |  ClkWiz, DDR4, UART Lite, JTAG, Proc System Reset, SD Card PMOD, and more.
       |* **Connections & Ports** - typed pin/port abstractions handle signal connections, clock domains, and resets.
       |* **FPGA targets** (`soct.system.vivado.fpga`) - board-specific definitions (e.g. ZCU104). Add new boards by extending `FPGA`.
       |* **TCL generation** - the `SOCTBdBuilder` traverses the design graph and emits the TCL commands needed to recreate the block design in Vivado.
       |
       |Custom components can be added by extending `BdComp` and implementing the TCL emission logic.
       |
       |---
       |
       |## Known Issues, Hints and Limitations
       |* Every time $sct emits a design it creates (or updates) a symbolic link at the project root `SOCTSystem-latest.cmake` pointing to the latest emitted `$SOCT_SYSTEM_CMAKE_FILE`.
       |All CMake projects in the repository use this if no explicit ${SOCTNames.SOCT_SYSTEM_CMAKE_KEY} variable is provided.
       |* The [firtool](https://github.com/llvm/circt/releases) binary needed for Chisel is only available for x86_64 architecture, requiring Rosetta to run on ARM64 hosts. Make sure it is installed and configured correctly.
       |To force installation run `softwareupdate --install-rosetta --agree-to-license` in the terminal.
       |* If you are using Docker via CLI, we recommend not opening the project in an IDE as it may cause issues with file permissions and generated files. Rather use two separate cloned repositories - one for CLI usage via Docker and one for IDE usage.
       |* If UART to the board fails, close the Vivado hardware manager. Sometimes the /dev/ttyUSB* disappears where `udevadm trigger` can help. We also advice against using USB hubs for the board connection as they can cause issues with the serial connection.
       |
       |""".stripMargin
  }

  def main(args: Array[String]): Unit = {
    val readmeContent = emit()

    val outPath = SOCTPaths.projectRoot.resolve("README.md")

    // Overwrite the file with the generated content.
    Files.write(outPath, readmeContent.getBytes(StandardCharsets.UTF_16))

    // Optional: print where we wrote it, which is handy when invoked via sbt.
    println(s"Wrote ${outPath.toAbsolutePath} (${readmeContent.length} chars)")
  }
}
