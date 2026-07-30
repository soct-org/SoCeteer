package soct

import soct.SOCTLauncher.SOCTConfig
import soct.SOCTNames.{DEFAULT_EXAMPLE_BINARY, SOCT_SIMULATOR_EXE, SOCT_SYSTEM_CMAKE_FILE}
import soct.build.{BuildInfo => info}
import soct.vivado.fpga.{FPGARegistry, PartRegistry}

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

  /** The kernel tag the Linux quick start clones: the version the in-tree patches
   * (`binaries/linux/patches`) and out-of-tree drivers are developed against. A
   * recommendation, not a requirement - newer kernels may work (the patches fail
   * loudly when they no longer apply). */
  private val linuxKernelTag = "v7.2-rc3"

  private def rel(path: Path): String = {
    SOCTPaths.projectRoot.relativize(path).toString
  }

  /** The branch the documentation links resolve against. */
  private val docsBranch = "main"

  /**
   * A markdown link to a documentation page in this repository, routed through
   * htmlpreview.github.io.
   *
   * GitHub serves repository `.html` files as source, so a plain repository link shows the
   * markup rather than the page; htmlpreview fetches the file and renders it (rewriting the
   * pages' relative script and stylesheet references along the way). Readers therefore get
   * the real page without cloning, while the documentation stays in the repository - there is
   * no published site to keep in sync.
   *
   * [[verifyAgainstApi]] checks that `page` exists, so a moved or renamed guide fails the
   * README build instead of shipping a dead link.
   *
   * @param page the project-root-relative path of the page (e.g. `docs/guides/video.html`)
   * @param text the link text
   * @return the markdown link
   */
  private def guide(page: String, text: String): String =
    s"[$text](https://htmlpreview.github.io/?$url/blob/$docsBranch/$page)"

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
       || | |
       ||----|----|
       || **Cores** | [RocketChip](https://github.com/chipsalliance/rocket-chip), [BOOM](https://github.com/riscv-boom/riscv-boom), [Gemmini](https://github.com/ucb-bar/gemmini) and more, emitted for [Verilator](https://www.veripool.org/wiki/verilator) simulation or [Vivado](https://www.amd.com/en/products/software/adaptive-socs-and-fpgas/vivado.html) FPGA synthesis |
       || **Block-design DSL** | Components, connections, clock domains and timing constraints written in Scala - every line of Vivado TCL is generated |
       || **Memory** | `--ext-mem-part` names the DIMM you inserted; capacity, device tree and address decode follow |
       || **Linux** | OpenSBI, kernel and BusyBox initramfs in one `BOOT.ELF`, loaded from SD by the stock boot ROM; device tree, memory map and console come from the design; `reboot` works (SBI SRST through the reset network) |
       || **Display** | The Linux console on a DisplayPort monitor (${guide("docs/guides/video.html", "guide")}); the preferred design (`soct.WithIncoherentVideoStream` + `soct.WithL2Cache`) has a frame fetch CPU load cannot starve |
       || **USB** | Host controller on by default on MPSoC boards: keyboard plus monitor make the board a self-contained terminal |
       || **Drivers** | Out-of-tree modules build with kbuild in one CMake target, land in the initramfs and index in clangd/CLion; an SD block driver ships in-tree (`/dev/mmcblk0`) |
       || **Toolchains** | CMake projects for boot ROMs and bare-metal programs; a separate LLVM/musl project for everything Linux |
       || **Chisel** | edu.berkeley.cs ${chisel3s.mkString(", ")} and org.chipsalliance ${otherChisels.mkString(", ")} |
       || **Runs anywhere** | Docker images for x86_64 and ARM64; native on Linux, macOS and Windows |
       |
       |### Documentation
       |
       |This README is only the quick start. The full documentation lives in the repository and
       |reads online through htmlpreview: **${guide("docs/docs.html", "the documentation site")}**
       |for the guides and the API reference - or open `docs/docs.html` from your clone
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
       |Step 3 is automated by the `verilator.build` target, which emits the design and then configures
       |and builds the simulator in one go (into `$simBuildDir`):
       |
       |```bash
       |sbt "runMain $slPath --target verilator.build"
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
Open the generated project (`workspace/<config>/$exampleBoard/vivado-project`), run synthesis and
       |implementation, and program the bitstream.
       |
       |To build without opening the GUI, pick a build target instead of `vivado`: `vivado.syn`
       |(synthesis) or `vivado.bs` (through `write_bitstream`), with `--vivado-parallel <jobs>` for
       |the job count. The build runs **detached** - the launcher prints its log path and a follow
       |command (`tail -f` / `Get-Content -Wait`) and returns immediately:
       |
       |```bash
       |sbt "runMain $slPath --target vivado.bs --board $exampleBoard --vivado-parallel 8 --vivado /path/to/vivado"
       |```
       |
       |Add `--use-remote-vivado` (with `--ssh-config`/`--remote-dir`) to run the build on a remote host;
       |follow the remote log it prints, then pull the results back with `--sfr`.
       |
       |Programs are then loaded over JTAG
       |(`<program>-flash` targets) or from the SD card - the stock `sd-boot` ROM loads a `BOOT.ELF`
       |application at reset. See the ${guide("docs/guides/binaries.html", "Binaries guide")}.
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
       |${guide("docs/guides/fpga-memory.html", "FPGA Memory & Custom DDR4")}. Supported boards:
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
       |# 1. Drop in the source trees - plain checkouts, deliberately not submodules (the kernel
       |#    tree alone would dominate every clone of this repository). The kernel version is the
       |#    one the in-tree patches and drivers are developed against.
       |git clone --depth 1 --branch $linuxKernelTag https://github.com/gregkh/linux.git ${path("binaries")}/linux/linux-stable
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
       |${guide("docs/guides/linux.html", "Booting Linux guide")}.
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

    // Documentation links are absolute (htmlpreview) and so escape the check above, but they
    // still name repository files - verify the paths they render.
    val previewPattern = s"""htmlpreview\\.github\\.io/\\?${java.util.regex.Pattern.quote(url)}/blob/$docsBranch/([^)#]+)""".r
    val badPages = previewPattern.findAllMatchIn(readme).map(_.group(1).trim).toSeq.distinct
      .filterNot(page => Files.exists(SOCTPaths.projectRoot.resolve(page)))
    if (badPages.nonEmpty) {
      throw new InternalBugException(
        s"README links documentation pages that do not exist: ${badPages.mkString(", ")}. " +
          "Generate the docs (`sbt buildDocs`) or fix the paths in SOCTReadmeBuilder.")
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
