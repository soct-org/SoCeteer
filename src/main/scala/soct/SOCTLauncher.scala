package soct

import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization.writePretty
import org.json4s.{DefaultFormats, Formats}

import java.nio.file.Path
import scala.io.Source


object SOCTLauncher {

  // JSON formats for serializing/deserializing
  implicit val formats: Formats = DefaultFormats + PathSerializer + TargetsSerializer

  // The current SocPaths based on the last parsed arguments
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

  def main(raw: Array[String]): Unit = SOCTParser.parse(raw, SOCTArgs()) match {
    case Some(parsed) =>
      // Set the log level of the logger
      configureLogging(parsed.logLevel.toUpperCase)

      // First check the terminating options
      if (parsed.getVersion) {
        println(version)
        return
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
