package soct

import scopt.OptionParser

import java.nio.file.{Path, Paths}

// Define the supported targets
sealed trait Targets {
  // The name of the target (as used in the CLI)
  def name: String

  // The default bootrom path relative to the "binaries" directory - used for --build when invoking CMake
  def defaultBootrom: String
}

object Targets {
  /**
   * Vivado target for synthesis
   */
  case object Vivado extends Targets {
    val name: String = "vivado"
    val defaultBootrom: String = "sd-boot"
  }

  /**
   * Yosys target for synthesis
   */
  case object Yosys extends Targets {
    val name: String = "yosys"
    val defaultBootrom: String = "sd-boot"
  }

  /**
   * Verilator target for simulation
   */
  case object Verilator extends Targets {
    val name: String = "verilator"
    val defaultBootrom: String = "testchipip-boot"
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
                     baseConfig: String = classOf[RocketB1].getName,
                     xlen: Int = 64,
                     logLevel: String = logLevels(1), // info
                     singleVerilogFile: Boolean = false,
                     mabi32: String = "ilp32",
                     mabi64: String = "lp64",
                     target: Targets = Targets.Verilator,
                     bootrom: Option[String] = None,
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
                     // Simulation options
                     simTop: String = classOf[SOCTSimTop].getName,
                     overrideSimFiles: Boolean = true,
                     // Synthesis options
                     synTop: String = classOf[SOCTSynTop].getName,
                     // Vivado specific options
                     vivadoSettings: Option[Path] = None,
                     vivado: Option[Path] = None,
                     // Yosys specific options
                     // TODO add yosys options here
                     // Board specific options
                     board: Option[String] = None,
                     freqMHz: Option[Double] = None,
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
        // No changes needed for Verilator
        args
      case Targets.Vivado =>
        args.copy(singleVerilogFile = false)
      case Targets.Yosys =>
        args.copy(singleVerilogFile = true)
    }
  }

  private val defaultSOCTArgs = SOCTArgs()

  help("help").text("Prints this usage text")
  // General options
  opt[String]('o', "out-dir").action((x, c) => c.copy(workspaceDir = Paths.get(x).toAbsolutePath)).text(s"The directory to store the generated files. Default is ${defaultSOCTArgs.workspaceDir}.")
  opt[String]('c', "configs").action((x, c) => c.copy(baseConfig = x)).text(s"The base config to build - can include additional configs (i.e. Parameters) to add parts to the system. Comma separated list that. Default is ${defaultSOCTArgs.baseConfig}.")
  opt[String]('t', "target").action((x, c) => c.copy(target = Targets.parse(x))).text(s"Whether to simulate or synthesize the design using various backends. Available options: ${Targets.values.map(_.name).mkString(", ")}. Default is ${defaultSOCTArgs.target}.")
  opt[String]("bootrom").action((x, c) => c.copy(bootrom = Some(x))).text(s"The path to the bootrom binary to use. Must be relative to the \"binaries\" directory. Default is determined by the target:" +
    s" ${Targets.values.map(t => s"${t.name} -> ${t.defaultBootrom}").mkString(", ")}.")
  opt[Int]("xlen").action((x, c) => c.copy(xlen = x)).text(s"The xlen to use. Default is ${defaultSOCTArgs.xlen}. Allowed values are 32 and 64 - 32 adds ${classOf[freechips.rocketchip.rocket.WithRV32].getName} to the config.")
  opt[String]("ll")
    .action((x, c) => c.copy(logLevel = x))
    .validate(x =>
      if (logLevels.contains(x.toLowerCase)) success
      else failure(s"Invalid log level. Allowed: ${logLevels.mkString(", ")}")
    )
    .text(s"The log level to use. Options: ${logLevels.mkString(", ")}. Default is ${defaultSOCTArgs.logLevel}.")
  opt[Unit]("single-verilog-file").action((_, c) => c.copy(singleVerilogFile = true)).text(s"(Ignored for Chisel 3 compiler - it always outputs a single file) Generate a single verilog file instead of splitting it up into modules. Due to the way firtool handles things, this flag DISABLES ANY FORM OF VERIFICATION INCLUDING PRINTF. When emitting a verilog for a board, this flag is always enabled as verification is not possible anyway, it is only relevant for simulation.")
  opt[String]("mabi32").action((x, c) => c.copy(mabi32 = x)).text(s"The mabi to use for 32 bit bootrom. Default is ${defaultSOCTArgs.mabi32}.")
  opt[String]("mabi64").action((x, c) => c.copy(mabi64 = x)).text(s"The mabi to use for 64 bit bootrom. Default is ${defaultSOCTArgs.mabi64}.")
  // Firtool options
  opt[String]("firtool-path").action((x, c) => c.copy(firtoolPath = Some(Paths.get(x)))).text(s"The path to the firtool binary. Overrides the version. If not set, the version together with the firtool resolver will be used.")
  opt[String]("firtool-version").action((x, c) => c.copy(firtoolVersion = x)).text(s"The version of firtool to use. Only change if you encounter issues. Default is ${defaultSOCTArgs.firtoolVersion}.")
  opt[String]('a', "firtool-arg").unbounded().action((x, c) => c.copy(firtoolArgs = c.firtoolArgs :+ x)).text(s"Additional arguments to pass to firtool. Is only applied in the last lowering stage. Can be used multiple times.")
  // Simulation options
  opt[Unit]("no-override-sim-files").action((_, c) => c.copy(overrideSimFiles = false)).text(s"When generating a design to be used with simulation, DO NOT copy, and potentially overwrite the files to the simulation directory - Only keep them in the workspace directory.")
  opt[String]("sim-top").action((x, c) => c.copy(simTop = x)).text(s"The top module to use for simulation. Default is ${defaultSOCTArgs.simTop}.")
  // Synthesis options
  opt[String]("syn-top").action((x, c) => c.copy(synTop = x)).text(s"The top module to use for synthesis. Default is ${defaultSOCTArgs.synTop}.")
  // Vivado specific options
  opt[String]("vivado-settings").action((x, c) => c.copy(vivadoSettings = Some(Paths.get(x)))).text(s"The vivado settings file to run before executing vivado. Default is ${defaultSOCTArgs.vivadoSettings}.")
  opt[String]("vivado").action((x, c) => c.copy(vivado = Some(Paths.get(x)))).text(s"The vivado executable script to use. Default is ${defaultSOCTArgs.vivado}.")
  // Yosys specific options
  // TODO add yosys options here
  // Board specific options
  opt[String]('b', "board").action((x, c) => c.copy(board = Some(x))).text(s"The targeted board for the synthesis targets. See syn/boards for supported boards.")
  opt[Double]('f', "freq-mhz").action((x, c) => c.copy(freqMHz = Some(x))).text("The frequency of the system in MHz. Required for some synthesis targets.")
  // Terminating options
  opt[Unit]("version").action((_, c) => c.copy(getVersion = true)).text("Prints the version of the tool.")
  opt[Unit]("wtf").action((_, c) => c.copy(wtf = true)).text("What the firtool -- Prints the firtool help.")
}