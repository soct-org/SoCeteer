package soct

import chisel3.Module
import scopt.OptionParser

import java.nio.file.{Path, Paths}
import org.chipsalliance.cde.config
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import soct.system.sim.SOCTSimSystem
import soct.system.vivado.SOCTVivadoSystem
import soct.system.vivado.fpga.{FPGA, FPGARegistry}
import soct.system.yosys.SOCTYosysSystem
import freechips.rocketchip.subsystem.WithPeripheryBusFrequency

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
    val defaultTop: ChiselTop = Right(classOf[SOCTVivadoSystem])
  }

  /**
   * Yosys target for synthesis
   */
  case object Yosys extends Targets {
    val name: String = "yosys"
    val defaultBootrom: String = "sd-boot"
    val defaultTop: ChiselTop = Right(classOf[SOCTYosysSystem])
  }

  /**
   * Verilator target for simulation
   */
  case object Verilator extends Targets {
    val name: String = "verilator"
    val defaultBootrom: String = "testchipip-boot"
    val defaultTop: ChiselTop = Left(classOf[SOCTSimSystem])
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
                     vivadoProjectDir: Path = SOCTPaths.get("vivado-projects"),
                     baseConfig: config.Parameters = new RocketB1,
                     xlen: Int = 64,
                     logLevel: String = logLevels(1), // info
                     verboseChisel: Boolean = false,
                     singleVerilogFile: Boolean = false,
                     includeLocationInfo: Boolean = false,
                     target: Targets = Targets.Verilator,
                     userBootrom: Option[String] = None,
                     userTop: Option[ChiselTop] = None,
                     userMabi: Option[String] = None,

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
                     userFirtoolArgs: Seq[String] = Seq.empty,

                     // Simulation options
                     overrideSimFiles: Boolean = true,
                     // Vivado specific options
                     vivado: Option[Path] = None,
                     board: Option[Class[_ <: FPGA]] = None,
                     coreFreq: Option[BigInt] = Some(100 * 1000 * 1000), // Default to 100 MHz
                     peripheryFreq: BigInt = 100 * 1000 * 1000, // Default to 100 MHz
                     overrideVivadoProject: Boolean = true,
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
        if (args.singleVerilogFile && !SOCTUtils.isOldChiselAPI) {
          log.warn("Overriding --single-verilog-file for Vivado target - We do not support a single verilog file emitted by modern Chisel versions.")
        }
        args.copy(singleVerilogFile = false)
      case Targets.Yosys =>
        if (!args.singleVerilogFile) {
          log.warn("Overriding --single-verilog-file for Yosys target - Yosys requires a single verilog file.")
        }
        args.copy(singleVerilogFile = true)
    }
  }

  private val defaultSOCTArgs = SOCTArgs()

  help("help").text("Prints this usage text")
  // General options
  opt[String]('o', "out-dir").action((x, c) => c.copy(workspaceDir = Paths.get(x).toAbsolutePath)).text(s"The directory to store the generated files. Default is ${defaultSOCTArgs.workspaceDir}.")
  opt[String]("vivado-project-dir").action((x, c) => c.copy(vivadoProjectDir = Paths.get(x).toAbsolutePath)).text(s"The directory to store the generated vivado projects. Default is ${defaultSOCTArgs.vivadoProjectDir}.")
  opt[String]('c', "configs")
    .action((x, c) => c.copy(baseConfig = SOCTUtils.instantiateConfig(x)))
    .text(s"The config that determines what system to build (Rocket-Chip, Boom, Gemmini etc). Default is ${defaultSOCTArgs.baseConfig.getClass.getName}.")
  opt[Int]("xlen").action((x, c) => c.copy(xlen = x)).text(s"The xlen to use. Default is ${defaultSOCTArgs.xlen}. Allowed values are 32 and 64 - 32 adds ${classOf[freechips.rocketchip.rocket.WithRV32].getName} to the config.")
  opt[String]("ll")
    .action((x, c) => c.copy(logLevel = x))
    .validate(x =>
      if (logLevels.contains(x.toLowerCase)) success
      else failure(s"Invalid log level. Allowed: ${logLevels.mkString(", ")}")
    )
    .text(s"The log level to use. Options: ${logLevels.mkString(", ")}. Default is ${defaultSOCTArgs.logLevel}.")
  opt[Unit]("verbose-chisel").action((_, c) => c.copy(verboseChisel = true)).text(s"Enable verbose Chisel output during elaboration. Default is ${defaultSOCTArgs.verboseChisel}.")
  opt[Unit]("single-verilog-file").action((_, c) => c.copy(singleVerilogFile = true)).text(s"(Ignored for Chisel 3 compiler - it always outputs a single file) Generate a single verilog file instead of splitting it up into modules. Due to the way firtool handles things, this flag DISABLES ANY FORM OF VERIFICATION INCLUDING PRINTF.")
  opt[Unit]("include-location-info").action((_, c) => c.copy(includeLocationInfo = true)).text(s"Include location information (file and line number) as comments in the generated verilog/systemverilog file.")
  opt[String]('t', "target").action((x, c) => c.copy(target = Targets.parse(x))).text(s"Whether to simulate or synthesize the design using various backends. Available options: ${Targets.values.map(_.name).mkString(", ")}. Default is ${defaultSOCTArgs.target}.")
  opt[String]("bootrom").action((x, c) => c.copy(userBootrom = Some(x))).text(s"The path to the bootrom binary to use. Must be relative to the \"binaries\" directory. Default is determined by the target:" +
    s" ${Targets.values.map(t => s"${t.name} -> ${t.defaultBootrom}").mkString(", ")}.")
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


  // Firtool options
  opt[String]("firtool-path").action((x, c) => c.copy(firtoolPath = Some(Paths.get(x)))).text(s"The path to the firtool binary. Overrides the version. If not set, the version together with the firtool resolver will be used.")
  opt[String]("firtool-version").action((x, c) => c.copy(firtoolVersion = x)).text(s"The version of firtool to use. Only change if you encounter issues. Default is ${defaultSOCTArgs.firtoolVersion}.")
  opt[String]('a', "firtool-arg").unbounded().action((x, c) => c.copy(userFirtoolArgs = c.userFirtoolArgs :+ x)).text(s"Additional arguments to pass to firtool. Is only applied in the last lowering stage. Can be used multiple times.")

  // Simulation options
  opt[Unit]("no-override-sim-files").action((_, c) => c.copy(overrideSimFiles = false)).text(s"When generating a design to be used with simulation, DO NOT copy, and potentially overwrite the files to the simulation directory - Only keep them in the workspace directory.")

  // Vivado specific options
  opt[String]("vivado").action((x, c) => c.copy(vivado = Some(Paths.get(x)))).text(s"The vivado executable script to use. Default is ${defaultSOCTArgs.vivado}.")
  opt[String]('b', "board")
    .action((x, c) => c.copy(board = FPGARegistry.n2bOpt(x)))
    .validate(x =>
      if (FPGARegistry.n2bOpt(x).isDefined) success
      else failure(s"Invalid FPGA board $x. Available boards: ${FPGARegistry.getKnownBoards.mkString(", ")}.")
    )
    .text(s"The FPGA board to target for synthesis. Available boards: ${FPGARegistry.getKnownBoards.mkString(", ")}.")
  opt[String]("core-freq-Mhz").action((x, c) => c.copy(coreFreq = Some(BigInt((x.toDouble * 1000 * 1000).toInt))))
    .text(
      s"""
         |The frequency to use for the core clock in MHz as a floating point number.
         |This should only be used for simple designs where it is sufficient to set a single frequency for all bus clocks.
         |For more complex designs where the core is driven by multiple clocks, set the frequency of the bus clock(s) by adding the configs ${classOf[WithPeripheryBusFrequency].getClass.getName} etc. to the main config.
         |""".stripMargin
      )
  opt[String]("periphery-freq-Mhz").action((x, c) => c.copy(peripheryFreq = BigInt((x.toDouble * 1000 * 1000).toInt)))
    .text(
      s"""
         |The frequency to use for the periphery bus clock in MHz as a floating point number.
         |This is used for the bus clock(s) that drive the periphery devices like the SDCard controller and UART.
         |Default is ${defaultSOCTArgs.peripheryFreq / (1000 * 1000)} MHz.
         |""".stripMargin
    )
  opt[Unit]("no-override-vivado-project").action((_, c) => c.copy(overrideVivadoProject = false)).text(s"When generating a design for synthesis with vivado, DO NOT overwrite an existing vivado project in the workspace directory.")
  // Terminating options
  opt[Unit]("version").action((_, c) => c.copy(getVersion = true)).text("Prints the version of the tool.")
  opt[Unit]("wtf").action((_, c) => c.copy(wtf = true)).text("What the firtool -- Prints the firtool help.")
}