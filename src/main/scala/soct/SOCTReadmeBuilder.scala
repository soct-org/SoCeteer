package soct

import soct.build.{BuildInfo => info}
import soct.vivado.fpga.FPGARegistry

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * Generates the project README from the live project API.
 *
 * The README is intentionally a shop window: the pitch, the feature overview and the links
 * into the documentation. The how-to lives in the guides (docs/guides), the internals in the
 * systems pages (docs/systems) - the README duplicates neither. Every fact in the emitted
 * text is pulled from the real API (argument parser, registries, paths, build info), and
 * [[verifyAgainstApi]] checks the result against the API before writing - so the build of the
 * README FAILS LOUDLY when the project API drifts (a flag is renamed, a board disappears, a
 * documentation page moves) instead of publishing stale content.
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
   * @param page the project-root-relative path of the page (e.g. `docs/guides/setup.html`)
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
       |#### Generators
       |
       || | |
       ||----|----|
       || **RocketChip** | The [reference RISC-V core generator](https://github.com/chipsalliance/rocket-chip): in-order cores with caches, MMU and supervisor support; the default config |
       || **BOOM** | A [superscalar out-of-order RISC-V core](https://github.com/riscv-boom/riscv-boom), for when single-thread performance matters |
       || **Shuttle** | A [superscalar in-order RISC-V core](https://github.com/ucb-bar/shuttle) - more throughput than RocketChip without going out-of-order |
       || **Saturn** | A [RISC-V vector unit](https://github.com/ucb-bar/saturn-vectors) (RVV) that attaches to RocketChip and Shuttle cores |
       || **Gemmini** | A [systolic-array ML accelerator](https://github.com/ucb-bar/gemmini), attached to a core as a RoCC coprocessor |
       || **L2 cache** | [SiFive's inclusive last-level cache](https://github.com/sifive/block-inclusivecache-sifive), shared by all cores and added through a single config |
       || **Nail** | A [fault-injection and reliability-evaluation framework](https://gitlab.iti.uni-luebeck.de/pubs/nail) (built on [Chiffre](https://github.com/IBM/chiffre)): bit flips and stuck-at faults injected into the running design, controlled from software. Not a submodule - clone it into `generators/` and the build picks it up |
       |
       |#### System design
       |
       || | |
       ||----|----|
       || **Configs** | Cores are picked, sized and combined through Chisel configs; both Chisel generations are supported (edu.berkeley.cs ${chisel3s.mkString(", ")}, org.chipsalliance ${otherChisels.mkString(", ")}) |
       || **Block-design DSL** | Components, connections, clock domains and timing constraints written in Scala - every line of [Vivado](https://www.amd.com/en/products/software/adaptive-socs-and-fpgas/vivado.html) TCL is generated |
       || **Memory** | `--ext-mem-part` names the DIMM you inserted; capacity, device tree and address decode follow - including modules the board's preset does not know |
       || **Boards** | ${FPGARegistry.getKnownBoards.mkString(", ")} - a new board is one Scala definition |
       |
       |#### Running a design
       |
       || | |
       ||----|----|
       || **Simulation** | The design runs under [Verilator](https://www.veripool.org/wiki/verilator): host-bridged syscalls, waveform tracing, and live GDB debugging of the simulated SoC |
       || **FPGA builds** | The launcher drives Vivado from project generation to the finished bitstream, locally or on a remote build server |
       || **Runs anywhere** | Docker images for x86_64 and ARM64; native on Linux, macOS and Windows |
       |
       |#### Linux
       |
       || | |
       ||----|----|
       || **Boot image** | One `BOOT.ELF` - firmware, kernel and BusyBox userspace - loaded from the SD card or over JTAG; device tree, memory map and console come from the design, so one kernel serves every design |
       || **Shell image** | Boots into a BusyBox shell on the serial console and the monitor alike; `reboot` works |
       || **Persistent storage** | `soct` keeps a persistent environment on the SD card or a USB stick - files and shell history survive reboots |
       || **Drivers** | Out-of-tree kernel modules build with the rest in one CMake target and land in the boot image; an SD-card driver ships in-tree (`/dev/mmcblk0`) |
       || **Toolchains** | CMake projects for boot ROMs and bare-metal programs; a separate LLVM/musl project for everything Linux - toolchains are fetched or auto-detected |
       |
       |#### Display & peripherals
       |
       || | |
       ||----|----|
       || **Display** | The Linux console on a DisplayPort monitor (${guide("docs/guides/linux-monitor.html", "guide")}, ${guide("docs/systems/video.html", "internals")}), with a display that CPU load cannot starve |
       || **Video tools** | Runtime resolution switching (`fbmode`) and a framebuffer image viewer (`fbimg`) ship in the image |
       || **USB** | Host controller on by default on MPSoC boards: keyboard plus monitor make the board a self-contained terminal, and USB sticks can carry the persistent environment |
       |
       |### Get started
       |
       |```bash
       |git clone --recurse-submodules $gitUrl
       |# If already cloned without submodules: git submodule update --init --recursive
       |```
       |
       |⚠️ Don't open the project in an IDE before initializing submodules.
       |
       |Then follow ${guide("docs/guides/setup.html", "Setting up SoCeteer")}: host packages, the
       |IDE projects and a first generated design. Alternatively, the prebuilt Docker image
       |carries every host dependency ([Dockerfile](${path("dockerfile")})):
       |
       |```bash
       |docker run --rm -it -u $$(id -u):$$(id -g) -v "$root":$rootDocker -w $rootDocker ghcr.io/soct-org/soceteer:latest bash
       |```
       |
       |### Documentation
       |
       |**Guides** - step-by-step example runs:
       |
       |* ${guide("docs/guides/setup.html", "Setting up SoCeteer")} - from the clone to the IDE projects and a first generated design
       |* ${guide("docs/guides/bitstream.html", "From Design to Bitstream")} - generate a design and let the launcher drive Vivado to a bitstream
       |* ${guide("docs/guides/remote.html", "Remote Development")} - build and flash through a server that has Vivado and the board
       |* ${guide("docs/guides/linux-monitor.html", "Monitor & Linux Programs")} - the full run: Linux from SD, a DisplayPort console, persistent storage on the card
       |
       |**Systems** - per-subsystem internals and reference: the hardware flow, the block-design
       |DSL, memory, the Linux boot chain, the video pipeline and more, plus the glossary and the
       |Scaladoc API reference. Everything is on **${guide("docs/docs.html", "the documentation site")}**
       |(rendered through htmlpreview) - or open `docs/docs.html` from the clone (`sbt buildDocs`
       |regenerates the API reference). All launcher options: `sbt "runMain $slPath --help"`.
       |""".stripMargin
  }

  /**
   * Verify the emitted README against the live project API. This is what makes README
   * generation fail when the API drifts instead of silently publishing stale docs.
   *
   * Checks:
   *  - every `--flag` referenced in the README exists in [[SOCTParser]]'s usage
   *  - every repository file/directory referenced by a relative link exists
   *  - every documentation page linked through htmlpreview exists
   *
   * @param readme the emitted README content
   * @throws InternalBugException if any referenced flag or path no longer exists
   */
  def verifyAgainstApi(readme: String): Unit = {
    val usage = SOCTParser.usage

    // All --flags the README mentions must exist in the parser (ignore flags inside URLs).
    val flagPattern = """(?<![\w/])--([a-z][a-z0-9-]*)""".r
    val cliFlags = flagPattern.findAllMatchIn(readme).map(_.group(1)).toSet
    val ignored = Set(
      "recurse-submodules", "init", "recursive", // git flags in examples
      "rm", "it" // docker flags in examples
    )
    val missing = (cliFlags -- ignored).filterNot(f => f == "help" || usage.contains(s"--$f"))
    if (missing.nonEmpty) {
      throw new InternalBugException(
        s"README references launcher flags that no longer exist in SOCTParser: ${missing.toSeq.sorted.mkString("--", ", --", "")}. " +
          "Update SOCTReadmeBuilder to match the current CLI.")
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
