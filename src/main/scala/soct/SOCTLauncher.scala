package soct

import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization.writePretty
import org.json4s.{DefaultFormats, Formats}
import scopt.OptionParser

import java.nio.file.{Path, Paths}
import scala.io.Source


object SOCTLauncher {

  // Default parameters for the launcher
  private val logLevels = Seq("debug", "info", "warn", "error")

  // Define the supported targets
  sealed trait Targets {
    // The name of the target (as used in the CLI)
    def name: String

    // The default bootrom path relative to the "binaries" directory - used for --build when invoking CMake
    def defaultBootrom: String
  }

  object Targets {
    case object Vivado extends Targets {
      val name: String = "vivado"
      val defaultBootrom: String = "sd-boot"
    }

    case object Yosys extends Targets {
      val name: String = "yosys"
      val defaultBootrom: String = "sd-boot"
    }

    case object Verilator extends Targets {
      val name: String = "verilator"
      val defaultBootrom: String = "testchipip-boot"
    }

    val values: Seq[Targets] = Seq(Vivado, Yosys, Verilator)

    private def fromString(s: String): Option[Targets] = values.find(_.name == s.toLowerCase)

    def parse(s: String): Targets =
      fromString(s).getOrElse(throw new IllegalArgumentException(s"Invalid target: $s. Allowed: ${values.map(_.name).mkString(", ")}"))
  }

  implicit val formats: Formats = DefaultFormats + PathSerializer + TargetsSerializer

  case class SOCTArgs(
                       // General options
                       workspaceDir: Path = SOCTPaths.projectRoot.resolve("workspace"),
                       baseConfig: String = "soct.RocketB1",
                       xlen: Int = 64,
                       logLevel: String = logLevels(1), // info
                       useRocketCFiles: Boolean = false,
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
                       simTop: String = "soct.SOCTSimTop",
                       overrideSimFiles: Boolean = true,
                       // Synthesis options
                       synTop: String = "soct.SOCTSynTop",
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


  private val parser = new OptionParser[SOCTArgs]("SOCTLauncher") {
    help("help").text("Prints this usage text")
    // General options
    opt[String]('d', "dir").action((x, c) => c.copy(workspaceDir = Paths.get(x).toAbsolutePath)).text(s"The directory to store the generated files. Default is ${SOCTArgs().workspaceDir}.")
    opt[String]('c', "base-config").action((x, c) => c.copy(baseConfig = x)).text(s"The base config to build - can include additional configs (i.e. Parameters) to add parts to the system. Comma separated list that. Default is ${SOCTArgs().baseConfig}.")
    opt[String]('t', "target").action((x, c) => c.copy(target = Targets.parse(x))).text(s"Whether to simulate or synthesize the design using various backends. Available options: ${Targets.values.map(_.name).mkString(", ")}. Default is ${SOCTArgs().target}.")
    opt[String]("bootrom").action((x, c) => c.copy(bootrom = Some(x))).text(s"The path to the bootrom binary to use. Must be relative to the \"binaries\" directory. Default is determined by the target:" +
      s" ${Targets.values.map(t => s"${t.name} -> ${t.defaultBootrom}").mkString(", ")}.")
    opt[Int]("xlen").action((x, c) => c.copy(xlen = x)).text(s"The xlen to use. Default is ${SOCTArgs().xlen}.")
    opt[String]("log-level")
      .action((x, c) => c.copy(logLevel = x))
      .validate(x =>
        if (logLevels.contains(x.toLowerCase)) success
        else failure(s"Invalid log level. Allowed: ${logLevels.mkString(", ")}")
      )
      .text(s"The log level to use. Options: ${logLevels.mkString(", ")}. Default is ${SOCTArgs().logLevel}.")
    opt[Unit]("use-rocket-c-files").action((_, c) => c.copy(useRocketCFiles = true)).text(s"DO NOT delete the *.cc files that are emitted by addResource. Only add this flag if you want to use Chipyard and you know what you're doing.")
    opt[Unit]("single-verilog-file").action((_, c) => c.copy(singleVerilogFile = true)).text(s"(Ignored for Chisel 3 compiler - it always outputs a single file) Generate a single verilog file instead of splitting it up into modules. Due to the way firtool handles things, this flag DISABLES ANY FORM OF VERIFICATION INCLUDING PRINTF. When emitting a verilog for a board, this flag is always enabled as verification is not possible anyway, it is only relevant for simulation.")
    opt[String]("mabi32").action((x, c) => c.copy(mabi32 = x)).text(s"The mabi to use for 32 bit bootrom. Default is ${SOCTArgs().mabi32}.")
    opt[String]("mabi64").action((x, c) => c.copy(mabi64 = x)).text(s"The mabi to use for 64 bit bootrom. Default is ${SOCTArgs().mabi64}.")
    // Firtool options
    opt[String]("firtool-path").action((x, c) => c.copy(firtoolPath = Some(Paths.get(x)))).text(s"The path to the firtool binary. Overrides the version. If not set, the version together with the firtool resolver will be used.")
    opt[String]("firtool-version").action((x, c) => c.copy(firtoolVersion = x)).text(s"The version of firtool to use. Only change if you encounter issues. Default is ${SOCTArgs().firtoolVersion}.")
    opt[String]('a', "firtool-arg").unbounded().action((x, c) => c.copy(firtoolArgs = c.firtoolArgs :+ x)).text(s"Additional arguments to pass to firtool. Is only applied in the last lowering stage. Can be used multiple times.")
    // Simulation options
    opt[Unit]("no-override-sim-files").action((_, c) => c.copy(overrideSimFiles = false)).text(s"When generating a design to be used with simulation, DO NOT copy, and potentially overwrite the files to the simulation directory - Only keep them in the workspace directory.")
    opt[String]("sim-top").action((x, c) => c.copy(simTop = x)).text(s"The top module to use for simulation. Default is ${SOCTArgs().simTop}.")
    // Synthesis options
    opt[String]("syn-top").action((x, c) => c.copy(synTop = x)).text(s"The top module to use for synthesis. Default is ${SOCTArgs().synTop}.")
    // Vivado specific options
    opt[String]("vivado-settings").action((x, c) => c.copy(vivadoSettings = Some(Paths.get(x)))).text(s"The vivado settings file to run before executing vivado. Default is ${SOCTArgs().vivadoSettings}.")
    opt[String]("vivado").action((x, c) => c.copy(vivado = Some(Paths.get(x)))).text(s"The vivado executable script to use. Default is ${SOCTArgs().vivado}.")
    // Yosys specific options
    // TODO add yosys options here
    // Board specific options
    opt[String]('b', "board").action((x, c) => c.copy(board = Some(x))).text(s"The targeted board for the synthesis targets. See syn/boards for supported boards.")
    opt[Double]('f', "freq-mhz").action((x, c) => c.copy(freqMHz = Some(x))).text("The frequency of the system in MHz. Required for some synthesis targets.")
    // Terminating options
    opt[Unit]("version").action((_, c) => c.copy(getVersion = true)).text("Prints the version of the tool.")
    opt[Unit]("wtf").action((_, c) => c.copy(wtf = true)).text("What the firtool -- Prints the firtool help.")
  }

  // The current SocPaths, is set in main
  var currentSoCPaths: Option[SOCTPaths] = None

  case class Config(
                     args: SOCTArgs,
                     mabi: String,
                     var configs: Seq[String],
                     config: String,
                     configFull: String,
                   )

  object Config {
    def apply(args: SOCTArgs): Config = {
      val mabi = if (args.xlen == 32) args.mabi32 else args.mabi64
      val configs = args.baseConfig.split(',').map(_.trim).toSeq
      val config = configs.head.split('.').last
      val configFull = s"${config}-${args.xlen}"
      new Config(args, mabi, configs, config, configFull)
    }
  }

  // Generate the design for Vivado synthesis
  private def generateVivadoDesign(args: SOCTArgs, boardPaths: BoardSOCTPaths, config: Config): Unit = {
    log.info("Generating design for Vivado synthesis")
    log.debug(s"Using the following paths: ${boardPaths.toString}")

    val boardDtsFile = Source.fromFile(boardPaths.boardDts.toFile)
    val jsonParams = Source.fromFile(boardPaths.boardParams.toFile)
    implicit val formats: Formats = DefaultFormats // for automatic case class conversion
    var boardParams = parse(jsonParams.mkString).extract[BoardParams]
    // If rocket frequency is provided, override the board params
    if (args.freqMHz.isDefined) {
      boardParams = boardParams.copy(ROCKET_FREQ_MHZ = args.freqMHz)
    }
    assert(boardParams.ROCKET_FREQ_MHZ.isDefined, "No frequency provided - Either set \"ROCKET_FREQ_MHZ\" in the board params or provide it as an argument")

    if (args.xlen == 32) {
      config.configs +:= "freechips.rocketchip.rocket.WithRV32" // Prepend, so it is actually used
      config.configs :+= "soct.ExtMem32Bit"
    } else {
      config.configs :+= "soct.ExtMem64Bit"
    }
    config.configs :+= "soct.RocketSynBaseConfig"

    if (SOCTUtils.rmrfOpt(boardPaths.systemDir) > 0) {
      log.info(s"Removed existing files in ${boardPaths.systemDir}")
    }
    val tmpDir = boardPaths.systemDir.resolve("tmp")
    tmpDir.toFile.mkdirs()

    // Store these results in a temporary dir - they are only required to generate a dts of the system
    val tmpArtifacts = Transpiler.evalDesign(args.synTop, config, boardPaths, SOCTPaths.rocketBootrom)

    val boardDts = boardDtsFile.getLines().mkString("\n")

    val bootromImg: Path = SOCTUtils.compileBootrom(boardPaths, tmpArtifacts, config, Some(boardDts), Some(boardParams))

    SOCTUtils.rmrfOpt(tmpDir)

    Transpiler.evalDesign(args.synTop, config, boardPaths, bootromImg)

    Transpiler.emitLowFirrtl(config, boardPaths)

    val vivadoConfig = config.copy(args = config.args.copy(singleVerilogFile = true)) // Always generate single verilog file for boards
    Transpiler.emitVerilog(vivadoConfig, boardPaths, args.firtoolArgs)

    if (!chisel3.BuildInfo.version.startsWith("3.")) {
      log.warn("Vivado synthesis is not yet tested with this Chisel version - expect issues.")
    }
    val tclFile = SOCTVivado.generateTCLScript(boardPaths.systemDir, args.board.get, config.config, boardParams)
    SOCTVivado.wrapVHDL(boardPaths.systemDir, SOCTUtils.findVerilator(), boardPaths.verilogFile, config.config)
    if (args.vivado.isDefined) {
      SOCTVivado.generateProject(tclFile, args.vivado.get, args.vivadoSettings)
    } else {
      log.warn("No vivado path file provided. Not generating bitstream.")
    }
  }

  // Generate the design for Yosys synthesis
  private def generateYosysDesign(args: SOCTArgs, yosysPaths: YosysSOCTPaths, config: Config): Unit = {
    log.info("Generating design for Yosys synthesis")
    log.debug(s"Using the following paths: ${yosysPaths.toString}")

    if (args.xlen == 32) {
      config.configs +:= "freechips.rocketchip.rocket.WithRV32" // Prepend, so it is actually used
      config.configs :+= "soct.ExtMem32Bit"
    } else {
      config.configs :+= "soct.ExtMem64Bit"
    }
    config.configs :+= "soct.RocketSynBaseConfig"

    if (SOCTUtils.rmrfOpt(yosysPaths.systemDir) > 0) {
      log.info(s"Removed existing files in ${yosysPaths.systemDir}")
    }
    val tmpDir = yosysPaths.systemDir.resolve("tmp")
    tmpDir.toFile.mkdirs()

    // Store these results in a temporary dir - they are only required to generate a dts of the system
    val tmpArtifacts = Transpiler.evalDesign(args.synTop, config, yosysPaths, SOCTPaths.rocketBootrom)

    val bootromImg: Path = SOCTUtils.compileBootrom(yosysPaths, tmpArtifacts, config)

    SOCTUtils.rmrfOpt(tmpDir)

    Transpiler.evalDesign(args.synTop, config, yosysPaths, bootromImg)

    Transpiler.emitLowFirrtl(config, yosysPaths)

    val yosysConfig = config.copy(args = config.args.copy(singleVerilogFile = true)) // Always generate single verilog file for yosys
    Transpiler.emitVerilog(yosysConfig, yosysPaths, args.firtoolArgs)
  }

  // Generate the design for simulation
  private def generateSimDesign(args: SOCTArgs, simPaths: SimSOCTPaths, config: Config): Unit = {
    log.info("Generating design for simulation")
    log.debug(s"Using the following paths: ${simPaths.toString}")

    config.configs :+= "soct.RocketSimBaseConfig"

    if (args.xlen == 32) {
      config.configs +:= "freechips.rocketchip.rocket.WithRV32" // Comes first, so it is actually used
    }

    if (SOCTUtils.rmrfOpt(simPaths.systemDir) > 0) {
      log.info(s"Removed existing files in ${simPaths.systemDir}")
    }
    val tmpDir = simPaths.systemDir.resolve("tmp")
    tmpDir.toFile.mkdirs()

    // Store these results in a temporary dir - they are only required to generate a dts of the system
    val tmpArtifacts = Transpiler.evalDesign(args.simTop, config, simPaths, SOCTPaths.rocketBootrom)

    val bootromImg: Path = SOCTUtils.compileBootrom(simPaths, tmpArtifacts, config)

    SOCTUtils.rmrfOpt(tmpDir)

    Transpiler.evalDesign(args.simTop, config, simPaths, bootromImg)

    Transpiler.emitLowFirrtl(config, simPaths)

    Transpiler.emitVerilog(config, simPaths, args.firtoolArgs)

    if (!args.useRocketCFiles) {
      val ccFiles = SOCTUtils.listFilesWithExtension(simPaths.systemDir, ".cc")
      // Print the files that are going to be deleted
      ccFiles.foreach(f => log.info(s"Deleting ${f.getName}"))
      // and delete them
      ccFiles.foreach(_.delete())
    }

    if (args.overrideSimFiles) {
      val configsSimDir = SOCTPaths.projectRoot.resolve("sim").resolve("configs")
      if (!configsSimDir.toFile.exists()) {
        configsSimDir.toFile.mkdirs()
      }
      val configsSimDirConfig = configsSimDir.resolve(config.configFull)
      if (SOCTUtils.rmrfOpt(configsSimDirConfig) > 0) {
        log.info(s"Removed existing files in $configsSimDirConfig")
      }
      SOCTUtils.recCopy(simPaths.systemDir, configsSimDirConfig)
      log.info(s"Copied files to $configsSimDirConfig")
    }
  }

  def main(raw: Array[String]): Unit = parser.parse(raw, SOCTArgs()) match {
    case Some(parsed) =>
      // Set the log level of the logger
      configureLogging(parsed.logLevel.toUpperCase)

      // First check the terminating options
      if (parsed.getVersion) {
        throw new NotImplementedError("Version not implemented yet")
      }

      // Modify the launcher args to include the firtool path
      val args = parsed.copy(firtoolPath =
        Some(parsed.firtoolPath.getOrElse(SOCTUtils.findFirtool(parsed.firtoolVersion))))

      if (args.wtf) {
        SOCTUtils.printFirtoolHelp(args.firtoolPath.get.toString)
        return
      }

      val config = Config(args)
      val prettyConfig = writePretty(config).replace("\"", "").replace(",", "").replace("{", "").replace("}", "").replace(" : ", ": ").replace("\n  ", "\n").replace("\n\n", "\n-------------\n")
      log.info(s"Generating design with the following configuration:$prettyConfig")

      args.target match {
        case Targets.Verilator =>
          log.info("Targeting Verilator simulation")
          val simPaths = new SimSOCTPaths(args, config)
          currentSoCPaths = Some(simPaths)
          generateSimDesign(args, simPaths, config)
        case Targets.Vivado =>
          // Ensure that a board is provided
          if (args.board.isEmpty) {
            throw new IllegalArgumentException("No board provided for Vivado synthesis target. Please provide a board using the --board argument.")
          }
          log.info(s"Targeting Vivado synthesis for board ${args.board.get}")
          val synPaths = new BoardSOCTPaths(args, config)
          currentSoCPaths = Some(synPaths)
          generateVivadoDesign(args, synPaths, config)
        case Targets.Yosys =>
          log.info("Targeting Yosys synthesis")
          val synPaths = new YosysSOCTPaths(args, config)
          currentSoCPaths = Some(synPaths)
          generateYosysDesign(args, synPaths, config)
      }
    case None => // arguments are bad, error message will have been displayed
  }
}
