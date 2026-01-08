package soct

import chisel3.Module
import scopt.OptionParser
import soct.xilinx.FPGARegistry
import soct.xilinx.fpga.FPGA

import java.nio.file.{Path, Paths}
import org.chipsalliance.cde.config
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.LazyModule


// Define the supported targets
sealed trait Targets {
  /**
   * The name of the target as used on the command line
   */
  val name: String

  /**
   * The default bootrom path relative to the "binaries" directory - used for --build when invoking CMake
   */
  val defaultBootrom: String

  /**
   * The default top module class for this target
   */
  val defaultTop: ChiselTop
}

object Targets {
  /**
   * Vivado target for synthesis
   */
  case object Vivado extends Targets {
    val name: String = "vivado"
    val defaultBootrom: String = "sd-boot"
    val defaultTop = Right(classOf[SOCTSynTop])
  }

  /**
   * Yosys target for synthesis
   */
  case object Yosys extends Targets {
    val name: String = "yosys"
    val defaultBootrom: String = "sd-boot"
    val defaultTop = Right(classOf[SOCTYosysTop])
  }

  /**
   * Verilator target for simulation
   */
  case object Verilator extends Targets {
    val name: String = "verilator"
    val defaultBootrom: String = "testchipip-boot"
    val defaultTop = Left(classOf[SOCTSimTop])
  }

  /**
   * Parse a target from a string
   *
   * @param s String to parse
   * @return Corresponding Targets value
   * @throws IllegalArgumentException if the string does not correspond to a valid target
   */
  def parse(s: String): Targets =
    fromString(s).getOrElse(throw new IllegalArgumentException(s"Invalid target: $s. Allowed: ${values.map(_.name).mkString(", ")}"))

  /**
   * All supported target values
   */
  val values: Seq[Targets] = Seq(Vivado, Yosys, Verilator)

  private def fromString(s: String): Option[Targets] = values.find(_.name == s.toLowerCase)
}


case class SOCTArgs(
                     // General options
                     workspaceDir: Path = SOCTPaths.get("workspace"),
                     baseConfig: config.Parameters = new RocketB1,
                     xlen: Int = 64,
                     logLevel: String = logLevels(1), // info
                     singleVerilogFile: Boolean = false,
                     target: Targets = Targets.Verilator,
                     userBootrom: Option[String] = None,
                     // Firtool options
                     firtoolPath: Option[Path] = None,
                     firtoolVersion: String = chisel3.BuildInfo.firtoolVersion.flatMap {
                       v =>
                         val Seq(major, minor, _) = v.split('.').map(_.toInt).toSeq
                         if (major != 1) {
                           throw new RuntimeException(s"Unsupported firtool version $v. Only 1.x versions are supported.")
                         }
                         // Only allow versions greater than 1.75.0
                         if (minor > 75) Some(v) else None
                     }.getOrElse("1.75.0"), // Default to the version
                     firtoolArgs: Seq[String] = Seq.empty,
                     userTop: Option[ChiselTop] = None,
                     userMabi: Option[String] = None,
                     // Simulation options
                     overrideSimFiles: Boolean = true,
                     // Synthesis options
                     // Vivado specific options
                     vivadoSettings: Option[Path] = None,
                     vivado: Option[Path] = None,
                     board: Option[FPGA] = None,
                     freqsMHz: Seq[Double] = Seq(100.0),
                     // Terminating options
                     getVersion: Boolean = false, // Print the version of the tool
                     wtf: Boolean = false, // What the firtool - for debugging
                   )

object SOCTParser extends OptionParser[SOCTArgs]("SOCTLauncher") {

  /**
   * Modify the args based on the target
   *
   * @param args   The original args
   * @param target The target to modify for
   * @return The modified args
   */
  def modifyArgsBasedOnTarget(args: SOCTArgs, target: Targets): SOCTArgs = {
    target match {
      case Targets.Verilator =>
        args
      case Targets.Vivado =>
        args
      case Targets.Yosys =>
        args.copy(singleVerilogFile = true)
    }
  }

  private val defaultSOCTArgs = SOCTArgs()

  help("help").text("Prints this usage text")
  // General options
  opt[String]('o', "out-dir").action((x, c) => c.copy(workspaceDir = Paths.get(x).toAbsolutePath)).text(s"The directory to store the generated files. Default is ${defaultSOCTArgs.workspaceDir}.")
  opt[String]('c', "configs")
    .action((x, c) => c.copy(baseConfig = SOCTUtils.instantiateConfig(x)))
    .text(s"The config that determines what system to build (Rocket-Chip, Boom, Gemmini etc). Default is ${defaultSOCTArgs.baseConfig.getClass.getName}.")
  opt[String]('t', "target").action((x, c) => c.copy(target = Targets.parse(x))).text(s"Whether to simulate or synthesize the design using various backends. Available options: ${Targets.values.map(_.name).mkString(", ")}. Default is ${defaultSOCTArgs.target}.")
  opt[String]("bootrom").action((x, c) => c.copy(userBootrom = Some(x))).text(s"The path to the bootrom binary to use. Must be relative to the \"binaries\" directory. Default is determined by the target:" +
    s" ${Targets.values.map(t => s"${t.name} -> ${t.defaultBootrom}").mkString(", ")}.")
  opt[Int]("xlen").action((x, c) => c.copy(xlen = x)).text(s"The xlen to use. Default is ${defaultSOCTArgs.xlen}. Allowed values are 32 and 64 - 32 adds ${classOf[freechips.rocketchip.rocket.WithRV32].getName} to the config.")
  opt[String]("ll")
    .action((x, c) => c.copy(logLevel = x))
    .validate(x =>
      if (logLevels.contains(x.toLowerCase)) success
      else failure(s"Invalid log level. Allowed: ${logLevels.mkString(", ")}")
    )
    .text(s"The log level to use. Options: ${logLevels.mkString(", ")}. Default is ${defaultSOCTArgs.logLevel}.")
  opt[Unit]("single-verilog-file").action((_, c) => c.copy(singleVerilogFile = true)).text(s"(Ignored for Chisel 3 compiler - it always outputs a single file) Generate a single verilog file instead of splitting it up into modules. Due to the way firtool handles things, this flag DISABLES ANY FORM OF VERIFICATION INCLUDING PRINTF.")
  // Firtool options
  opt[String]("firtool-path").action((x, c) => c.copy(firtoolPath = Some(Paths.get(x)))).text(s"The path to the firtool binary. Overrides the version. If not set, the version together with the firtool resolver will be used.")
  opt[String]("firtool-version").action((x, c) => c.copy(firtoolVersion = x)).text(s"The version of firtool to use. Only change if you encounter issues. Default is ${defaultSOCTArgs.firtoolVersion}.")
  opt[String]('a', "firtool-arg").unbounded().action((x, c) => c.copy(firtoolArgs = c.firtoolArgs :+ x)).text(s"Additional arguments to pass to firtool. Is only applied in the last lowering stage. Can be used multiple times.")
  opt[String]("top")
    .action((x, c) => c.copy(userTop = {
      // The top can either be a class extending Module or LazyModule - we use reflection to determine which one it is
      val cls = Class.forName(x)
      if (classOf[Module].isAssignableFrom(cls)) {
        Some(Left(cls.asInstanceOf[Class[_ <: Module]]))
      } else if (classOf[LazyModule].isAssignableFrom(cls)) {
        Some(Right(cls.asInstanceOf[Class[_ <: LazyModule]]))
      } else {
        throw new IllegalArgumentException(s"Invalid top module class: $x. Must extend either chisel3.Module or diplomacy.LazyModule.")
      }
    }))
    .text(s"The fully qualified name (including the package path) of the top module class to use. Default is determined by the target: ${Targets.values.map(t => s"${t.name} -> ${t.defaultTop.fold(_.getName, _.getName)}").mkString(", ")}.")
  opt[String]("mabi").action((x, c) => c.copy(userMabi = Some(x))).text(s"The machine ABI to use (e.g., ilp32, lp64) to compile the bootrom.")
  // Simulation options
  opt[Unit]("no-override-sim-files").action((_, c) => c.copy(overrideSimFiles = false)).text(s"When generating a design to be used with simulation, DO NOT copy, and potentially overwrite the files to the simulation directory - Only keep them in the workspace directory.")
  // Vivado specific options
  opt[String]("vivado-settings").action((x, c) => c.copy(vivadoSettings = Some(Paths.get(x)))).text(s"The vivado settings file to run before executing vivado. Default is ${defaultSOCTArgs.vivadoSettings}.")
  opt[String]("vivado").action((x, c) => c.copy(vivado = Some(Paths.get(x)))).text(s"The vivado executable script to use. Default is ${defaultSOCTArgs.vivado}.")
  opt[String]('b', "board")
    .action((x, c) => c.copy(board = FPGARegistry.resolve(x)))
    .validate(x =>
      if (FPGARegistry.resolve(x).isDefined) success
      else failure(s"Invalid FPGA board. Available boards: ${FPGARegistry.availableBoards.mkString(", ")}")
    )
    .text(s"The FPGA board to target for synthesis. Available boards: ${FPGARegistry.availableBoards.mkString(", ")}.")
  opt[String]("freq-mhz").action((x, c) => c.copy(freqsMHz = Seq(x.split(",").map(_.toDouble): _*)))
    .validate(x => {
      val freqs = x.split(",").map(_.toDouble)
      if (freqs.forall(_ > 0)) success
      else failure("Frequencies must be positive numbers.")
    })
    .text(s"The target frequency in MHz for the design. Either a single frequency for all cores or a comma separated list of frequencies for each core for the config provided. Default is ${defaultSOCTArgs.freqsMHz.head} MHz.")
  // Terminating options
  opt[Unit]("version").action((_, c) => c.copy(getVersion = true)).text("Prints the version of the tool.")
  opt[Unit]("wtf").action((_, c) => c.copy(wtf = true)).text("What the firtool -- Prints the firtool help.")
}