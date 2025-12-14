package soct

import soct.SOCTLauncher.Config

import java.nio.file.{Files, Path, Paths}


/**
 * Companion object for SOCTPaths containing utility methods that do not depend on runtime arguments
 */
object SOCTPaths {
  /**
   * Path to the root of the soceteer project
   * Can be overridden by setting the SOCETEER_ROOT environment variable
   */
  def projectRoot: Path = {
    val envVar = System.getenv("SOCETEER_ROOT")
    if (envVar != null) {
      val path = Paths.get(envVar)
      if (Files.exists(path)) return path
    }
    Paths.get("").toAbsolutePath
  }

  /**
   * Path to the rocket-chip directory, depending on the chisel version used
   */
  val rocketChipDir: Path = if (chisel3.BuildInfo.version.startsWith("3.")) {
    SOCTPaths.projectRoot.resolve("generators").resolve("rocket-chip-chisel3")
  } else {
    SOCTPaths.projectRoot.resolve("generators").resolve("rocket-chip")
  }

  /**
   * Path to the default Rocket bootrom image, only used during first elaboration before generating the actual bootrom
   */
  val rocketBootrom: Path = rocketChipDir.resolve("bootrom").resolve("bootrom.img")

  /**
   * Name of the generated system (used for naming files and several artifacts)
   */
  val systemName: String = "riscv_system"


  /**
   * All trivial paths use a simple map
   */
  def get(name: String): Path = name match {
    case "syn" => SOCTPaths.projectRoot.resolve("syn")
    case "sim" => SOCTPaths.projectRoot.resolve("sim")
    case "workspace" => SOCTPaths.projectRoot.resolve("workspace")
    case "binaries" => SOCTPaths.projectRoot.resolve("binaries")
    case "shared" => SOCTPaths.projectRoot.resolve("shared")
    case "vhdlsrcs" => SOCTPaths.projectRoot.resolve("src").resolve("main").resolve("resources") // TODO move to syn?
    case "boards" => get("syn").resolve("boards")
    case "tclsrcs" => get("syn").resolve("tclsrcs")
    case "vsrcs" => get("syn").resolve("vsrcs")
    case "FindVerilator.cmake" => get("shared").resolve("cmake").resolve("FindVerilator.cmake")
    case "binaries-build" => get("binaries").resolve("cmake-build-bootrom")
    case "programs-build" => get("binaries").resolve("cmake-build-programs")
    case _ => throw new InternalBugException(s"Unknown path name: $name")
  }
}

/**
 * Abstract class containing all the important paths used during the SOCT flow
 *
 * @param args SOCTArgs containing user-provided arguments
 */
abstract class SOCTPaths(args: SOCTArgs) {
  /**
   * Path to the system directory where all generated files will be stored
   */
  val systemDir: Path

  /**
   * Path to the firtool binary
   */
  def firtoolBinary: Path = args.firtoolPath.getOrElse(throw new InternalBugException("Firtool path not set in LauncherArgs"))

  /**
   * Path to the generated Verilog file
   */
  def verilogFile: Path = systemDir.resolve(s"${SOCTPaths.systemName}.v")

  /**
   * Path to the generated low firrtl file (only when using Berkeley chisel)
   */
  def lowFirrtlFile: Path = systemDir.resolve(s"${SOCTPaths.systemName}.opt.lo.fir")

  /**
   * Path to the generated firrtl file
   */
  def firrtlFile: Path = systemDir.resolve(s"${SOCTPaths.systemName}.fir")

  /**
   * Path to the generated firrtl annotations file - mainly used when using Berkeley chisel
   */
  def annoFile: Path = systemDir.resolve(s"${SOCTPaths.systemName}.anno.json")

  /**
   * Path to the generated device tree source file
   */
  def dtsFile: Path = systemDir.resolve(s"${SOCTPaths.systemName}.dts")

  /**
   * Path to the generated bootrom image file (contains the plain instructions to be loaded at boot)
   */
  def bootromImgFile: Path = systemDir.resolve("bootrom.img")

  // Custom to string for easier debugging (thanks ChatGPT)
  override def toString: String = {
    s"""
       |SOCTPaths:
       |  projectRoot: ${SOCTPaths.projectRoot}
       |  rocketChipDir: ${SOCTPaths.rocketChipDir}
       |  rocketBootrom: ${SOCTPaths.rocketBootrom}
       |  systemName: ${SOCTPaths.systemName}
       |  systemDir: $systemDir
       |  firtoolBinary: $firtoolBinary
       |  verilogFile: $verilogFile
       |  lowFirrtlFile: $lowFirrtlFile
       |  firrtlFile: $firrtlFile
       |  annoFile: $annoFile
       |  dtsFile: $dtsFile
       |  bootromImgFile: $bootromImgFile
       |""".stripMargin
  }
}

/**
 * SOCTPaths for Yosys synthesis flow
 *
 * @param args   SOCTArgs containing user-provided arguments
 * @param config Config used for this synthesis
 */
private class YosysSOCTPaths(args: SOCTArgs, config: Config) extends SOCTPaths(args) {
  // For example: workspace/RocketB1-64/system-yosys
  val systemDir: Path = args.workspaceDir.resolve(config.configFull).resolve("system-yosys")
}

private class BoardSOCTPaths(args: SOCTArgs, config: Config) extends SOCTPaths(args) {
  // For example: workspace/RocketB1-64/system-zcu104
  val boardDts: Path =  SOCTPaths.get("boards").resolve(args.board.get).resolve("bootrom.dts")
  val boardParams: Path = SOCTPaths.get("boards").resolve(args.board.get).resolve("params.json")
  val systemDir: Path = args.workspaceDir.resolve(config.configFull).resolve(s"system-${args.board.get}")
}

private class SimSOCTPaths(args: SOCTArgs, config: Config) extends SOCTPaths(args) {
  // For example: workspace/RocketB1-64/sim
  val systemDir: Path = args.workspaceDir.resolve(config.configFull).resolve("sim")
}