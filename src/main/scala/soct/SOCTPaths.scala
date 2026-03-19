package soct

import soct.SOCTLauncher.SOCTConfig
import soct.SOCTNames._
import soct.SOCTPaths.paths
import soct.system.vivado.fpga.FPGARegistry

import java.nio.file.{Files, Path, Paths}


object SOCTNames {
  val SOCETEER_ROOT_ENV_VAR: String = "SOCETEER_ROOT"
  val SOCT_SYSTEM_CMAKE_FILE: String = "SOCTSystem.cmake"
  val SOCT_SYSTEM_CMAKE_KEY: String = "SOCT_SYSTEM"
  val LATEST_SOCT_SYSTEM_CMAKE_FILE: String = "SOCTSystem-latest.cmake"
  val SOCT_SIMULATOR_EXE = "simulator"
  val DEFAULT_EXAMPLE_BINARY = "hello-hart" // Default example binary to build for tests and in README
}


/**
 * Companion object for SOCTPaths containing utility methods that do not depend on runtime arguments
 */
object SOCTPaths {
  /**
   * Path to the root of the soceteer project
   * Can be overridden by setting the SOCETEER_ROOT environment variable
   */
  def projectRoot: Path = {
    val envVar = System.getenv(SOCETEER_ROOT_ENV_VAR)
    if (envVar != null) {
      val path = Paths.get(envVar)
      if (Files.exists(path)) return path
    }
    Paths.get("").toAbsolutePath
  }

  /**
   * Get a predefined path by name
   *
   * @param name   Name of the path to retrieve
   * @param create Whether to create the path if it does not exist (only applicable for dynamic paths)
   * @return Path corresponding to the given name
   * @throws InternalBugException if the name is unknown
   */
  def get(name: String, create: Boolean = true): Path = {
    val p = paths.getOrElse(name, throw new InternalBugException(s"Unknown path name: $name"))
    if (create) {
      p.toFile.mkdirs() // create the directory if it doesn't exist
    }
    p
  }

  /**
   * Validate that all static paths exist
   *
   * @throws InternalBugException if any static path does not exist
   */
  def validateStaticPaths(): Unit = {
    (base ++ derived).foreach { case (name, path) =>
      if (!Files.exists(path)) {
        throw new InternalBugException(s"Static path '$name' does not exist at expected location: $path")
      }
    }
  }

  private lazy val root: Path = SOCTPaths.projectRoot

  private val base: Map[String, Path] = Map(
    "sim" -> root.resolve("sim"),
    "binaries" -> root.resolve("binaries"),
    "shared" -> root.resolve("shared"),
    "generators" -> root.resolve("generators"),
    "dockerfile" -> root.resolve("Dockerfile"),
  )

  private val derived: Map[String, Path] = Map(
    "FindVERILATOR.cmake" -> base("shared").resolve("cmake").resolve("FindVERILATOR.cmake"),
    "default-toolchain" -> base("shared").resolve("cmake").resolve("toolchain-riscv.cmake"),
    "verilator" -> base("shared").resolve("verilator")
  )

  // Dynamic paths that are created during the execution of the program - these cannot be validated at startup since they may not exist yet
  private val baseDyn: Map[String, Path] = Map(
    "test-run-dir" -> {
      val envOverride = System.getenv("SOCT_TEST_RUN_DIR") // Allow overriding the test run directory with an environment variable, to avoid too long paths being passed to verilator on Windows.
      if (envOverride != null && envOverride.nonEmpty) Paths.get(envOverride)
      else root.resolve("test_run_dir")
    }
  )

  private val derivedDyn: Map[String, Path] = Map(
    "test-workspace" -> baseDyn("test-run-dir").resolve("workspace")
  )

  private lazy val paths: Map[String, Path] = base ++ derived ++ baseDyn ++ derivedDyn
}


/**
 * Base class for SOCTPaths - contains any common logic or utility methods that don't depend on runtime arguments.
 */
abstract class SOCTPathsBase {
  /**
   * Implementation of the systemDir path. Superseded by the systemDir val to allow for additional logic in the implementation
   */
  protected def systemDirImpl(prefixPath: Path): Path

  /**
   * Path to the system directory where all generated files will be stored
   * If the user provided an output directory via the --out-dir argument, that will be used as the system directory. Otherwise, the system directory will be determined based on the workspace directory and the config name (and FPGA board name for Vivado).
   * The system directory will be created if it does not exist, and an exception will be thrown if the provided output directory is not a directory or is not writable.
   *
   * @return Path to the system directory where all generated files will be stored
   */
  def systemDir: Path

  /**
   * Initialize any subdirectories needed for this synthesis flow (e.g., for Vivado, we need a vivado-srcs subdirectory for the generated TCL scripts and sources).
   */
  def createSubdirsImpl(): Unit

  /**
   * Path to the generated bootrom image file (contains the plain instructions to be loaded at boot)
   */
  def bootromImgFile: Path = systemDir.resolve("bootrom.img")

  /**
   * Path to the directory where generated ELF files for this system will be stored
   */
  def elfsDir: Path = systemDir.resolve("elfs")

  /**
   * Path to the generated SOCTSystem.cmake file.
   */
  def soctSystemCMakeFile: Path = systemDir.resolve(SOCT_SYSTEM_CMAKE_FILE)

  /**
   * Path to a symlink (or copy) of the latest generated SOCTSystem.cmake file.
   * This is useful for downstream users who want to use the generated CMake file without needing to know the exact system directory
   */
  def latestSoctSystemCMakeFile: Path = SOCTPaths.projectRoot.resolve(LATEST_SOCT_SYSTEM_CMAKE_FILE)

  /**
   * Path to a build directory that can be used for temporary files during the build process (e.g., when building the bootrom with CMake).
   * This directory is also added to the SOCTSystem.cmake file as SOCT_BUILD_DIR so it can be used for verilator builds, etc.
   */
  def buildDir: Path = systemDir.resolve("build")

  /**
   * Create any necessary subdirectories for this synthesis flow.
   * This should be called after instantiating the SOCTPaths object, and will create the system directory if it doesn't exist, as well as any additional subdirectories needed for this synthesis flow (e.g., vivado-srcs for Vivado).
   */
  final def createSubdirs(): Unit = {
    systemDir.toFile.mkdirs()
    createSubdirsImpl()
  }
}


/**
 * Companion object for SOCTPaths for fixed out dir - behaves like SOCTPaths where args.userOutDir is set. Mainly used for tests.
 */
object SOCTPathsBase {
  def apply(outDir: Path): SOCTPathsBase = {
    new SOCTPathsBase() {
      override def systemDir: Path = outDir
      override protected def systemDirImpl(prefixPath: Path): Path = outDir
      override def createSubdirsImpl(): Unit = {
        outDir.toFile.mkdirs()
      }
    }
  }
}



/**
 * Abstract class containing all the important paths used during the SOCT flow
 *
 * @param args SOCTArgs containing user-provided arguments
 * @param config Config used for this synthesis
 */
abstract class SOCTPaths(args: SOCTArgs, config: SOCTConfig) extends SOCTPathsBase {

  override def systemDir: Path = {
    if (args.userOutDir.isDefined) {
      args.userOutDir.get.toFile.mkdirs()
      if (!Files.isDirectory(args.userOutDir.get)) {
        throw new IllegalArgumentException(s"Provided output directory ${args.userOutDir.get} is not a directory")
      }
      if (!Files.isWritable(args.userOutDir.get)) {
        throw new IllegalArgumentException(s"Provided output directory ${args.userOutDir.get} is not writable")
      }
      args.userOutDir.get
    } else {
      systemDirImpl(args.workspaceDir)
    }
  }

  /**
   * Path to the firtool binary
   */
  def firtoolBinary: Path = args.firtoolPath.getOrElse(throw new InternalBugException("Firtool path not set in LauncherArgs"))

  /**
   * Path to the generated verilog/systemverilog files
   */
  def verilogSrcDir: Path = systemDir.resolve("vsrcs")

  /**
   * Path to the generated low firrtl file (only when using Berkeley chisel)
   */
  def lowFirrtlFile: Path = systemDir.resolve(s"${config.topModuleName}.opt.lo.fir")

  /**
   * Path to the generated firrtl file
   */
  def firrtlFile: Path = systemDir.resolve(s"${config.topModuleName}.fir")

  /**
   * Path to the generated firrtl annotations file - mainly used when using Berkeley chisel
   */
  def annoFile: Path = systemDir.resolve(s"${config.topModuleName}.anno.json")

  /**
   * Path to the generated device tree source file
   */
  def dtsFile: Path = systemDir.resolve(s"${config.topModuleName}.dts")

  /**
   * Path to the generated device tree blob file
   */
  def dtbFile: Path = systemDir.resolve(s"${config.topModuleName}.dtb")
}



class YosysSOCTPaths(args: SOCTArgs, config: SOCTConfig) extends SOCTPaths(args, config) {
  protected def systemDirImpl(prefixPath: Path): Path = prefixPath.resolve(config.configName).resolve("system-yosys")

  override def createSubdirsImpl(): Unit = {}
}



class VivadoSOCTPaths(args: SOCTArgs, config: SOCTConfig) extends SOCTPaths(args, config) {
  private val fpgaBoardName: String = args.board match {
    case Some(boardClass) => FPGARegistry.b2n(boardClass)
    case None => throw new InternalBugException("FPGA board not set in SOCTArgs for VivadoSOCTPaths")
  }

  protected def systemDirImpl(prefixPath: Path): Path = prefixPath.resolve(config.configName).resolve(fpgaBoardName)

  /**
   * Path to the directory containing the generated source files for Vivado (e.g., the init.tcl file, the block design tcl file, constraints, etc.)
   */
  val vivadoSourceDir: Path = systemDir.resolve("vivado-srcs")

  /**
   * Path to the Vivado project directory - where the Vivado project files like the .xpr file will be stored
   */
  val vivadoProjectDir: Path = args.vivadoWorkspace.resolve(config.configName).resolve(fpgaBoardName)

  /**
   * Path to the TCL file that initializes the Vivado project (loading sources, constraints, etc.)
   */
  val tclInitFile: Path = vivadoSourceDir.resolve("init.tcl")

  /**
   * Path to the TCL file that loads the block design for the top module in Vivado
   */
  val defaultBdGenerator: Path = vivadoSourceDir.resolve(s"${config.topModuleName}_bd.tcl")


  /**
   * Path to the TCL file that synthesizes the top module in Vivado
   */
  val defaultSynthGenerator: Path = vivadoSourceDir.resolve(s"${config.topModuleName}_synth.tcl")


  /**
   * Path to the TCL file that implements the top module in Vivado
   */
  val defaultImplGenerator: Path = vivadoSourceDir.resolve(s"${config.topModuleName}_impl.tcl")


  /**
   * Path to the generated XDC file containing constraints for the top module in Vivado
   */
  val xdcDir: Path = vivadoSourceDir.resolve("xdc")


  override def createSubdirsImpl(): Unit = {
    vivadoSourceDir.toFile.mkdirs()
    vivadoProjectDir.toFile.mkdirs()
    xdcDir.toFile.mkdirs()
  }
}



class SimSOCTPaths(args: SOCTArgs, config: SOCTConfig) extends SOCTPaths(args, config) {
  protected def systemDirImpl(prefixPath: Path): Path = prefixPath.resolve(config.configName).resolve("sim")

  override def createSubdirsImpl(): Unit = {}
}