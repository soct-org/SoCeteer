package soct

// Import the generated BuildInfo as info (short alias for convenience):

import soct.SOCTNames.SOCT_SYSTEM_CMAKE_FILE
import soct.build.{BuildInfo => info}

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
  val root = "."

  val chiselVersions = info.supportedChiselVersions.split(",").map(_.trim).toList
  val chisel3s = chiselVersions.filter(_.startsWith("3"))
  val otherChisels = chiselVersions.filterNot(_.startsWith("3"))

  val sl = SOCTLauncher.getClass.getSimpleName.stripSuffix("$")
  val slPath = SOCTLauncher.getClass.getCanonicalName.stripSuffix("$")
  val slFilePath = info.scalaMain + "/" + SOCTLauncher.getClass.getCanonicalName.stripSuffix("$").replace(".", "/") + ".scala"

  val defaultArgs = SOCTArgs()
  val defaultConfigPath = defaultArgs.baseConfig.getClass.getCanonicalName
  val defaultConfig = s"${defaultArgs.baseConfig}-${defaultArgs.xlen}"
  val soctCmakePath = s"${rel(defaultArgs.workspaceDir)}/$defaultConfig/sim/${SOCT_SYSTEM_CMAKE_FILE}"

  def rel(path: Path): String = {
    root + "/" + SOCTPaths.projectRoot.relativize(path).toString
  }

  def path(s: String): String = {
    rel(SOCTPaths.get(s))
  }

  def emit(): String = {
    s"""A framework for the design and deployment of Chisel-based RISC-V-based SoCs.
       |
       |> [!IMPORTANT]
       |> This project is in early development and is NOT ready for any serious use. We recomment using $sct for experimentation and learning purposes only at this time.
       |> For a more stable experience, please use the tagged releases, which are available on GitHub. The latest release is [v${info.version}]($url/releases/tag/v${info.version}).
       |
       |
       |> [!NOTE]
       |> Please take a look at [Known Issues](#known-issues-and-limitations) for a list of current limitations and issues.
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
       |* Support for edu.berkeley.cs.chisel (${chisel3s.mkString(", ")}) and org.chipsalliance.chisel (${otherChisels.mkString(", ")})
       |
       |* CMake projects for building bootroms and binaries to run on the generated designs (both in simulation and on FPGA)
       |
       |* Very fast design generation (several times faster than other SoC builders)
       |
       |* Docker images available for easy setup and use, with support for both x86_64 and ARM64 hosts (including Apple Silicon)
       |
       |* Cross-platform support for Linux, macOS(ARM / x86_64), and Windows (even native!)
       |
       |---
       |
       |## Setup and Dependencies
       |
       |---
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
       |This may take significant time due to [Gemmini's](https://github.com/ucb-bar/gemmini) dependencies. For faster
       |initialization when branch switching is not required, use `--depth 1`.
       |
       |⚠️ Don't open the project in an IDE before initializing submodules, as it may add directories for uninitialized
       |submodules which can cause issues with cloning.
       |
       |
       |Next, ensure you have the necessary tools installed. For a full setup refer to the [Dockerfile](${path("dockerfile")}).
       |
       |---
       |
       |#### Java (11 or newer)
       |
       |* (IDEA IntelliJ users) Download via `Actions -> Find Action -> Download JDK`, select at
       |  `File -> Project Structure -> SDK`.
       |* (CLI users) Set `JAVA_HOME` to the JDK installation path, add `$$JAVA_HOME/bin` to `PATH`.
       |  Verify with `java -version` and `javac -version`.
       |
       |---
       |
       |#### Scala and SBT
       |
       |* (IDEA IntelliJ users) Install the Scala plugin via `File -> Settings -> Plugins`, search for "Scala", install, and
       |  restart the IDE.
       |* (CLI users) Install [Scala + SBT](https://www.scala-lang.org/download/)
       |  or [SBT only](https://www.scala-sbt.org/1.x/docs/Setup.html).
       |  Ensure `sbt`, `scala`, and `java` are available in the system `PATH`.
       |
       |---
       |
       |#### CMake
       |
       |* Required to build the CMake projects for the bootrom and other binaries that run on the generated designs. Must be on the system `PATH`.
       |
       |```bash
       |# Ubuntu/Debian
       |sudo apt-get install cmake
       |# Arch Linux
       |sudo pacman -S cmake
       |# macOS
       |brew install cmake
       |# Windows (via Chocolatey)
       |choco install cmake
       |```
       |
       |---
       |
       |#### Device Tree Compiler
       |
       |* Required to compile device tree source files (.dts) into binary blobs (.dtb).
       |
       |```bash
       |# Ubuntu/Debian
       |sudo apt-get install device-tree-compiler
       |# Arch Linux
       |sudo pacman -S dtc
       |# macOS
       |brew install dtc
       |# Windows (via Chocolatey)
       |choco install dtc-msys2
       |```
       |
       |---
       |
       |#### RISC-V Toolchain [(xpack-dev-tools)](https://github.com/xpack-dev-tools/riscv-none-elf-gcc-xpack)
       |* Required for compiling RISC-V programs and the bootroms, downloaded automatically when running $sct.
       |
       |---
       |
       |#### Verilator (for Simulation)
       |
       |* A maintained, compatible [submodule](${path("verilator")}) is built automatically when running $sct for simulation the first time.
       |* Requires `flex` and `bison`:
       |
       |```bash
       |# Ubuntu/Debian
       |sudo apt-get install flex bison
       |# Arch Linux
       |sudo pacman -S flex bison
       |# macOS
       |brew install flex bison
       |# Windows (via Chocolatey)
       |choco install winflexbison3
       |```
       |
       |---
       |
       |
       |#### Vivado (for FPGA Deployment)
       |
       |* Required for synthesizing designs for Xilinx FPGAs. Used by $sct to emit project files.
       |* Download from [here](https://www.xilinx.com/support/download/index.html/content/xilinx/en/downloadNav/vivado-design-tools.html)
       |
       |---
       |
       |## Quick Start
       |
       |Here's a quick start guide to get you up and running with $sct.
       |By default, running the launcher without any args will emit a RocketChip SoC with the default configuration ($defaultConfigPath) - a single RocketChip core for simulation (by default with 64-bit XLEN)
       |After running the launcher, you can find the emitted files (like the FIRRTL and Verilog description, regmaps and the device tree) in the `workspace/$defaultConfig/sim` directory.
       |We then use the CMake project in ${path("binaries")}") to emit a simple program that runs on the generated design in simulation.
       |For this, the CMake project in ${path("sim")} will build a Verilator-based simulator for the generated design, and you can run the emitted program on the simulator to see it in action.
       |
       |> [!NOTE]
       |> $sct emits a CMake file for each emitted design named ${SOCT_SYSTEM_CMAKE_FILE} which contains information about the emitted design, such as the CPU architecture, the number of cores etc.
       |> This simplifies the process of building binaries as commonly used variables are already defined for you and don't need to be extracted from the device tree blob.
       |> For our example, the emitted ${SOCT_SYSTEM_CMAKE_FILE} file is located at `$soctCmakePath`.
       |---
       |
       |#### Emit a Design (IntelliJ IDEA)
       |1. Open $sct (the root directory) in IntelliJ IDEA with the Scala plugin.
       |2. Navigate to the launcher class $sl [$slFilePath]($slFilePath)
       |3. Press on the green play button next to the `main` method to run the project.
       |    * In the case the play button is not visible, reload the SBT project (`Help -> Find Action -> sbt` and click
       |    `Sync all SBT projects`).
       |    * To change the arguments passed to $sct, edit the Configuration in the top right corner (or `Help -> Find Action -> Edit Configurations`).
       |
       |---
       |
       |#### Emit a Design (CLI)
       |0. (Docker only) Pull the latest image with `docker pull ghcr.io/soct-org/soceteer:latest` or build it locally from the [Dockerfile](${path("dockerfile")}).
       |    Then create a container with the repository mounted and a terminal attached:
       |    ```bash
       |    docker run --rm -it \\
       |      -v "<path-to-repo>":/mnt \\
       |      -w /mnt \\
       |      ghcr.io/soct-org/soceteer:latest \\
       |      bash
       |    ```
       |    * `-it` gives you a TTY + interactive prompt.
       |    * Adding `-u $$(id -u):$$(id -g)` makes file ownership inside the container match your host user
       |     (optional, but prevents permission issues with generated files).
       |    * `<path-to-repo>` should be the absolute path to the cloned repository on your host machine.
       |    * NOTE: If you are using Docker via CLI, we recommend not opening the project in an IDE as it may cause issues with file permissions and generated files. Rather use two separate cloned repositories - one for CLI usage via Docker and one for IDE usage.
       |
       |1. Run the main method of the $sl
       |    1. (Using sbt) Run `sbt "runMain $slPath"` in the terminal from the root directory.
       |    Additional arguments for the main method can be passed after the class path, for example: `sbt "runMain $slPath --help"` to see all available options.
       |    2. (Using a JAR) Build the project with `sbt assembly` and run the generated JAR with `java -jar <path-to-jar> $slPath`.
       |        The `path-to-jar` is set in the `assemblyOutputPath` setting in `build.sbt`, and defaults to `target/assembly/chisel-<chiselVersion>/soceteer-<version>.jar`.
       |
       |---
       |
       |#### Building Binaries (CLion or VSCode)
       |
       |
       |
       |
       |
       |
       |
       |---
       |
       |#### Building Binaries (CLI)
       |
       |1. Create a ´build´ directory: `mkdir -p ${path("binaries")}/build`
       |   To initialize the CMake project for our example, run `cmake -S ${path("binaries")} -B ${path("binaries")}/build -DSOCT_SYSTEM=${soctCmakePath}` from the project root directory.
       |
       |6. Open the [sim](sim) directory as a separate project in CLion or VSCode. Build the simulator using CMake.
       |    * Make sure to select the Release build type for **significantly better performance** (left of the configuration
       |      selector in CLion, Debug by default).
       |7. Open the [examples](binaries) directory as a separate project in CLion or VSCode. Build one of the example programs
       |   using CMake.
       |8. Run the simulator and pass the compiled ELF binary as an argument.
       |
       |
       |
       |
       |## Known Issues and Limitations
       |
       |
       |
       |""".stripMargin
  }

  def main(args: Array[String]): Unit = {
    val readmeContent = emit()

    val outPath = SOCTPaths.projectRoot.resolve("README-NEW.md")

    // Overwrite the file with the generated content.
    Files.write(outPath, readmeContent.getBytes(StandardCharsets.UTF_16))

    // Optional: print where we wrote it, which is handy when invoked via sbt.
    println(s"Wrote ${outPath.toAbsolutePath} (${readmeContent.length} chars)")
  }
}
