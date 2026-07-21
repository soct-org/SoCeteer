package soct

import soct.SOCTLauncher.SOCTConfig
import soct.SOCTNames.{DEFAULT_EXAMPLE_BINARY, SOCT_SIMULATOR_EXE, SOCT_SYSTEM_CMAKE_FILE}
import soct.build.{BuildInfo => info}
import soct.system.vivado.fpga.{FPGARegistry, PartRegistry}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * Generates the project README from the live project API.
 *
 * The README is intentionally a quick-start guide only (simulation + FPGA); everything else
 * lives on the local docs site (docs/docs.html). Every fact in the emitted text is pulled from
 * the real API (argument parser, registries, paths, build info), and [[verifyAgainstApi]] checks
 * the result against the API before writing - so the build of the README FAILS LOUDLY when the
 * project API drifts (a flag is renamed, a board disappears, a path moves) instead of publishing
 * stale instructions.
 */
object SOCTReadmeBuilder {
  private val name = info.name
  private val sct = s"**$name**"
  private val url = "https://github.com/soct-org/SoCeteer"
  private val gitUrl = url + ".git"
  private val root = "$PWD"
  private val rootDocker = "/soceteer"

  private val chiselVersions = info.supportedChiselVersions.split(",").map(_.trim).toList
  private val chisel3s = chiselVersions.filter(_.startsWith("3"))
  private val otherChisels = chiselVersions.filterNot(_.startsWith("3"))

  private val slPath = SOCTLauncher.getClass.getCanonicalName.stripSuffix("$")
  private val slFilePath = info.scalaMain + "/" + slPath.replace(".", "/") + ".scala"

  private val defaultArgs = SOCTArgs()
  private val defaultConfigPath = defaultArgs.baseConfig.getClass.getCanonicalName

  private val paths = new SimSOCTPaths(defaultArgs, SOCTConfig(defaultArgs))

  private val soctCmakePath = s"${rel(paths.systemDir)}/$SOCT_SYSTEM_CMAKE_FILE"

  private val defaultBin = DEFAULT_EXAMPLE_BINARY
  private val defaultBinPath = rel(paths.elfsDir.resolve(s"$defaultBin.elf"))

  private val simBuildDir = rel(paths.buildDir.resolve("sim-build"))
  private val progBuildDir = rel(paths.buildDir.resolve("prog-build"))
  private val cmakeSoctSystemDef = s"-DSOCT_SYSTEM=/path/to/${rel(paths.soctSystemCMakeFile)}"

  /** The board used in the FPGA quick start; must be registered in [[FPGARegistry]]. */
  private val exampleBoard = "ZCU104"

  /** The example board's workspace directory and linux build/output paths for the Linux quick start. */
  private val fpgaSystemDir = s"${rel(paths.systemDir.getParent)}/$exampleBoard"
  private val linuxBuildDir = s"$fpgaSystemDir/build/linux-build"
  private val fpgaElfsDir = s"$fpgaSystemDir/${paths.elfsDir.getFileName}"

  /** The non-preset DIMM used in the FPGA quick start; its capacity must resolve via [[PartRegistry]]. */
  private val exampleMemPart = "MTA16ATF2G64HZ-2G3"

  private def rel(path: Path): String = {
    SOCTPaths.projectRoot.relativize(path).toString
  }

  /**
   * Resolve a named static project path relative to the project root.
   *
   * @param s the path name (see [[SOCTPaths.get]])
   * @return the project-root-relative path string
   * @throws InternalBugException if the name is unknown
   */
  private def path(s: String): String = {
    rel(SOCTPaths.get(s))
  }

  /**
   * Render the README content.
   *
   * @return the README markdown
   */
  def emit(): String = {
    s"""<p align="center">SoCeteer - A framework for designing and running RISC-V-based SoCs on FPGA and in Simulation, built on top of Chisel.<br/>From a Scala design to a Linux shell on your board.</p>
       |
       |<p align="center">
       |  <a href="https://github.com/soct-org/SoCeteer/actions/workflows/on-pr.yml"><img src="https://github.com/soct-org/SoCeteer/actions/workflows/on-pr.yml/badge.svg?branch=main" alt="CI" /></a><a href="https://github.com/soct-org/SoCeteer/actions/workflows/on-tag.yml"><img src="https://github.com/soct-org/SoCeteer/actions/workflows/on-tag.yml/badge.svg?branch=main" alt="Release Workflow" /></a><a href="https://github.com/orgs/soct-org/packages/container/package/soceteer"><img src="https://img.shields.io/badge/GHCR-soceteer-blue?logo=docker" alt="GHCR Package" /></a>
       |</p>
       |
       |> [!IMPORTANT]
       |> This project is in early development and is NOT ready for any serious use. We recommend using $sct for experimentation and learning purposes only at this time.
       |> For a more stable experience, please use the tagged releases.
       |
       |### Features
       |
       |* Included generators contain: **[RocketChip](https://github.com/chipsalliance/rocket-chip)**,
       | **[BOOM](https://github.com/riscv-boom/riscv-boom)**,
       | **[Gemmini](https://github.com/ucb-bar/gemmini)** and more!
       |* Emit designs for Simulation using [Verilator](https://www.veripool.org/wiki/verilator) or
       | FPGA synthesis using [Vivado](https://www.amd.com/en/products/software/adaptive-socs-and-fpgas/vivado.html)
       |* Built-in Vivado Block Design DSL: components, ports, connections, clock domains, timing constraints and TCL generation - all in Scala, without hand-writing TCL
       |* Custom DIMM support: select the inserted memory module with `--ext-mem-part`; capacities, device tree and address decode follow automatically
       |* **Boots Linux on your design**: OpenSBI, the kernel and a BusyBox initramfs are packed into a single
       | `BOOT.ELF` that the stock boot ROM loads from the SD card - device tree, memory map and console
       | configuration are generated from the design, and `reboot` round-trips through the SoC's reset network (SBI SRST)
       |* Out-of-tree kernel driver workflow: modules build with kbuild against the shared kernel tree in one
       | CMake target, are packed into the initramfs and loaded at boot, and index in clangd/CLion for
       | comfortable driver development; an SD-card block driver ships in-tree (`/dev/mmcblk0`)
       |* Optional DisplayPort video output (`--with-config soct.WithVideoStream`): a VDMA-driven framebuffer
       | in DRAM streamed into the PS DisplayPort live input ([guide](docs/guides/video.html))
       |* Support for edu.berkeley.cs.chisel (${chisel3s.mkString(", ")}) and org.chipsalliance.chisel (${otherChisels.mkString(", ")})
       |* CMake projects for bootroms and bare-metal programs (simulation and FPGA), plus a separate
       | LLVM/musl CMake project for everything Linux: kernel, firmware, userspace and drivers
       |* Docker images for x86_64 and ARM64 hosts; runs natively on Linux, macOS and Windows
       |
       |### Documentation
       |
       |This README is only the quick start. The full documentation is local to the repository -
       |open **[docs/docs.html](docs/docs.html)** in a browser for the guides and the API reference
       |(regenerate with `sbt buildDocs`). All launcher options: `sbt "runMain $slPath --help"`.
       |
       |---
       |
       |## Setup
       |
       |Clone with submodules and install the system dependencies (full reference: [Dockerfile](${path("dockerfile")})):
       |
       |```bash
       |git clone --recurse-submodules $gitUrl
       |# If already cloned without submodules: git submodule update --init --recursive
       |```
       |
       |⚠️ Don't open the project in an IDE before initializing submodules.
       |
       |* **Java 11+ & [SBT](https://www.scala-sbt.org/1.x/docs/Setup.html)** (or IntelliJ IDEA with the Scala plugin)
       |* **CMake & Ninja**, **Device Tree Compiler (dtc)**, **Flex & Bison**:
       |
       |```bash
       |# Ubuntu/Debian:  sudo apt-get install cmake ninja-build device-tree-compiler flex bison
       |# Arch Linux:     sudo pacman -S cmake ninja dtc flex bison
       |# macOS:          brew install cmake ninja dtc flex bison
       |# Windows:        choco install cmake ninja dtc-msys2 winflexbison3
       |```
       |
       |The RISC-V toolchain ([xpack-dev-tools](https://github.com/xpack-dev-tools/riscv-none-elf-gcc-xpack)) and
       |[Verilator](${path("verilator")}) are downloaded/built automatically on first use.
       |
       |---
       |
       |## Quick Start: Simulation
       |
       |```bash
       |# 1. Emit the design (default config, Verilator target)
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
       |Every emit writes `$soctCmakePath` (variables for arch, core count, ABI, paths) and updates the
       |`SOCTSystem-latest.cmake` symlink at the project root, which all CMake projects fall back to when no
       |explicit `${SOCTNames.SOCT_SYSTEM_CMAKE_KEY}` is passed. Pick a different system with
       |`--config <class>` (default: `$defaultConfigPath`) and `--xlen 32/64`.
       |
       |**IntelliJ IDEA / CLion:** run the `main` method in [$slFilePath]($slFilePath), then open
       |`${path("binaries")}` and `${path("sim")}` as CLion projects with `$cmakeSoctSystemDef` in the CMake options.
       |
       |---
       |
       |## Quick Start: FPGA ($exampleBoard)
       |
       |```bash
       |# Emit the design, generate the Vivado project, block design and constraints:
       |sbt "runMain $slPath --target vivado --board $exampleBoard --vivado /path/to/vivado"
       |```
       |
       |Open the generated project (`workspace/<config>/$exampleBoard/vivado-project`), run synthesis and
       |implementation, and program the bitstream. Programs are then loaded over JTAG
       |(`<program>-flash` targets) or from the SD card - the stock `sd-boot` ROM loads a `BOOT.ELF`
       |application at reset. See the [Binaries guide](docs/guides/binaries.html).
       |
       |**Using the DIMM that is actually inserted:** Vivado's board flow locks the DDR4 controller to the
       |board-preset module ($exampleBoard preset: 4 GiB). If your board carries a different DIMM, pass its
       |Vivado part name - the design switches to a custom DDR4 interface and sizes memory, device tree and
       |address decode from the part:
       |
       |```bash
       |# Example: 16 GiB dual-rank SODIMM in the $exampleBoard slot
       |sbt "runMain $slPath --target vivado --board $exampleBoard --ext-mem-part $exampleMemPart --vivado /path/to/vivado"
       |```
       |
       |Details (part registry, custom interface internals, on-hardware validation with `mem-test`):
       |[FPGA Memory & Custom DDR4](docs/guides/fpga-memory.html). Supported boards:
       |${FPGARegistry.getKnownBoards.mkString(", ")} - add new boards by extending `FPGA` and registering them in `FPGARegistry`.
       |
       |---
       |
       |## Quick Start: Linux
       |
       |The FPGA designs boot Linux: `BOOT.ELF` is an OpenSBI firmware wrapping the kernel, the
       |design's device tree and a BusyBox initramfs - loaded from the SD card by the boot ROM
       |like any other program.
       |
       |```bash
       |# 1. Drop in the source trees (plain checkouts, recent versions)
       |git clone --depth 1 https://git.kernel.org/pub/scm/linux/kernel/git/stable/linux.git ${path("binaries")}/linux/linux-stable
       |git clone --depth 1 https://github.com/riscv-software-src/opensbi.git ${path("binaries")}/linux/opensbi
       |
       |# 2. Configure and build (host clang + ld.lld with RISC-V support; musl sysroot bootstraps itself)
       |cmake -S ${path("binaries")}/linux -B $linuxBuildDir -DSOCT_SYSTEM=/path/to/$fpgaSystemDir/$SOCT_SYSTEM_CMAKE_FILE
       |cmake --build $linuxBuildDir --target shell-boot-elf
       |
       |# 3. Copy $fpgaElfsDir/shell.BOOT.ELF to a FAT-formatted SD card as BOOT.ELF and reset the board
       |```
       |
       |The `shell` image boots into an interactive BusyBox shell on the
       |UART, with the SD card itself available as `/dev/mmcblk0` through the bundled out-of-tree
       |driver. Every program under [binaries/linux/userspace/](binaries/linux/userspace) that
       |includes `initram.cmake` becomes its own bootable image (`<name>-boot-elf`, running as
       |`/init`); kernel modules under [binaries/linux/drivers/](binaries/linux/drivers) are built
       |against the shared kernel build and packed into the initramfs automatically. Toolchains,
       |host requirements, kernel patches and JTAG-flashing images without an SD card:
       |[Booting Linux guide](docs/guides/linux.html).
       |
       |---
       |
       |## Hints
       |* The [firtool](https://github.com/llvm/circt/releases) binary needed for Chisel is x86_64-only; ARM64 macOS needs Rosetta (`softwareupdate --install-rosetta --agree-to-license`).
       |* If UART to the board fails, close the Vivado hardware manager; if `/dev/ttyUSB*` disappears, `udevadm trigger` can help. Avoid USB hubs for the board connection.
       |* On Windows, Verilator requires Visual Studio and building the simulator requires MinGW. For command-length errors during Verilator builds, move the project to a shorter path or pass `--single-verilog-file`.
       |""".stripMargin
  }

  /**
   * Verify the emitted README against the live project API. This is what makes README
   * generation fail when the API drifts instead of silently publishing stale docs.
   *
   * Checks:
   *  - every `--flag` referenced in the README exists in [[SOCTParser]]'s usage
   *  - the example board is registered in [[FPGARegistry]]
   *  - the example memory part's capacity resolves via [[PartRegistry]]
   *  - every repository file/directory referenced by a relative link exists
   *
   * @param readme the emitted README content
   * @throws InternalBugException if any referenced flag, board, part or path no longer exists
   */
  def verifyAgainstApi(readme: String): Unit = {
    val usage = SOCTParser.usage

    // All --flags the README mentions must exist in the parser (ignore flags inside URLs).
    val flagPattern = """(?<![\w/])--([a-z][a-z0-9-]*)""".r
    val cliFlags = flagPattern.findAllMatchIn(readme).map(_.group(1)).toSet
    val ignored = Set(
      "recurse-submodules", "branch", "rm", "it", "init", "recursive", "depth", // git/docker flags in examples
      "build", "target", "install-rosetta", "agree-to-license" // cmake/macOS flags in examples
    )
    val missing = (cliFlags -- ignored).filterNot(f => f == "help" || usage.contains(s"--$f"))
    if (missing.nonEmpty) {
      throw new InternalBugException(
        s"README references launcher flags that no longer exist in SOCTParser: ${missing.toSeq.sorted.mkString("--", ", --", "")}. " +
          "Update SOCTReadmeBuilder to match the current CLI.")
    }

    if (FPGARegistry.n2bOpt(exampleBoard).isEmpty) {
      throw new InternalBugException(s"README example board '$exampleBoard' is not registered in FPGARegistry (known: ${FPGARegistry.getKnownBoards.mkString(", ")}).")
    }

    if (PartRegistry.capacityOf(exampleMemPart).isEmpty) {
      throw new InternalBugException(s"README example memory part '$exampleMemPart' does not resolve in PartRegistry.")
    }

    // Every relative markdown link target must exist in the repository.
    val linkPattern = """\]\((?!https?://)([^)#]+)\)""".r
    val badLinks = linkPattern.findAllMatchIn(readme).map(_.group(1).trim).toSeq.distinct
      .filterNot(target => Files.exists(SOCTPaths.projectRoot.resolve(target)))
    if (badLinks.nonEmpty) {
      throw new InternalBugException(s"README references repository paths that do not exist: ${badLinks.mkString(", ")}")
    }
  }

  /**
   * Emit, verify and write the README to the project root.
   *
   * @param args ignored
   * @throws InternalBugException if the README no longer matches the project API (see [[verifyAgainstApi]])
   */
  def main(args: Array[String]): Unit = {
    val readmeContent = emit()

    verifyAgainstApi(readmeContent)

    val outPath = SOCTPaths.projectRoot.resolve("README.md")
    Files.write(outPath, readmeContent.getBytes(StandardCharsets.UTF_8))
    println(s"Wrote ${outPath.toAbsolutePath} (${readmeContent.length} chars)")
  }
}
