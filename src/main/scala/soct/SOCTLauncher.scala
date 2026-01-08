package soct

import org.chipsalliance.cde.config.Parameters
import org.json4s.{DefaultFormats, Formats}
import soct.xilinx.SOCTVivado

object SOCTLauncher {

  // JSON formats for serializing/deserializing
  implicit val formats: Formats = DefaultFormats + PathSerializer + TargetsSerializer

  case class SOCTConfig(
                         args: SOCTArgs,
                         mabi: String,
                         topModule: ChiselTop,
                         var params: Parameters,
                         configName: String,
                       )

  object SOCTConfig {
    def apply(args: SOCTArgs): SOCTConfig = {
      val mabi = args.userMabi.getOrElse(if (args.xlen == 32) "ilp32" else "lp64")
      val params = new WithHartBootFreqMHz(args.freqsMHz) ++ args.baseConfig
      val topModule = args.userTop.getOrElse(args.target.defaultTop)
      val configName = s"${args.baseConfig.getClass.getSimpleName}-${args.xlen}"
      new SOCTConfig(args, mabi, topModule, params, configName)
    }
  }

  // Generate the design for Vivado synthesis
  private def generateVivadoDesign(args: SOCTArgs, boardPaths: BoardSOCTPaths, config: SOCTConfig): Unit = {
    log.info("Generating design for Vivado synthesis")
    log.debug(s"Using the following paths: ${boardPaths.toString}")

    if (args.xlen == 32) {
      config.params = config.params.orElse(new ExtMem32Bit)
    } else {
      config.params = config.params.orElse(new ExtMem64Bit)
    }
    config.params = config.params.orElse(new soct.RocketSynBaseConfig)

    if (SOCTUtils.rmrfOpt(boardPaths.systemDir) > 0) {
      log.info(s"Removed existing files in ${boardPaths.systemDir}")
    }

    Transpiler.evalDesign(config, boardPaths)

    Transpiler.emitLowFirrtl(config, boardPaths)

    Transpiler.emitVerilog(config, boardPaths, args.firtoolArgs)

    SOCTVivado.generate(boardPaths, config)
  }

  // Generate the design for Yosys synthesis
  private def generateYosysDesign(args: SOCTArgs, yosysPaths: YosysSOCTPaths, config: SOCTConfig): Unit = {
    throw new NotImplementedError("Yosys synthesis target has been removed for the time being.")
  }

  // Generate the design for simulation
  private def generateSimDesign(args: SOCTArgs, simPaths: SimSOCTPaths, config: SOCTConfig): Unit = {
    log.info("Generating design for simulation")
    log.debug(s"Using the following paths: ${simPaths.toString}")

    config.params = config.params.orElse(new soct.RocketSimBaseConfig)

    if (SOCTUtils.rmrfOpt(simPaths.systemDir) > 0) {
      log.info(s"Removed existing files in ${simPaths.systemDir}")
    }

    Transpiler.evalDesign(config, simPaths)

    Transpiler.emitLowFirrtl(config, simPaths)

    Transpiler.emitVerilog(config, simPaths, args.firtoolArgs)

    if (args.overrideSimFiles) {
      val configsSimDir = SOCTPaths.projectRoot.resolve("sim").resolve("configs") // TODO change path
      if (!configsSimDir.toFile.exists()) {
        configsSimDir.toFile.mkdirs()
      }
      val configsSimDirConfig = configsSimDir.resolve(config.configName)
      if (SOCTUtils.rmrfOpt(configsSimDirConfig) > 0) {
        log.info(s"Removed existing files in $configsSimDirConfig")
      }
      SOCTUtils.recCopy(simPaths.systemDir, configsSimDirConfig)
      log.info(s"Copied files to $configsSimDirConfig")
    }
  }

  def main(raw: Array[String]): Unit = SOCTParser.parse(raw, SOCTArgs()) match {
    case Some(parsed) =>
      configureLogging(parsed.logLevel.toUpperCase)
      SOCTPaths.validateStaticPaths()
      var args = SOCTParser.modifyArgsBasedOnTarget(parsed, parsed.target)

      // First check the terminating options:
      if (args.getVersion) {
        println(version)
        return
      }

      // Modify the launcher args to include the firtool path
      args = args.copy(firtoolPath =
        Some(args.firtoolPath.getOrElse(SOCTUtils.findFirtool(args.firtoolVersion))))

      if (args.wtf) {
        SOCTUtils.printFirtoolHelp(args.firtoolPath.get.toString)
        return
      }

      // Modify the params:
      val config = SOCTConfig(args)
      config.params = config.params.orElse(new WithSOCTConfig(config))
      if (args.xlen == 32) {
        config.params = config.params.orElse(new freechips.rocketchip.rocket.WithRV32)
      }

      args.target match {
        case Targets.Verilator =>
          log.info("Targeting Verilator simulation")
          val simPaths = new SimSOCTPaths(args, config)
          config.params = config.params.orElse(new WithSOCTPaths(simPaths))
          generateSimDesign(args, simPaths, config)
        case Targets.Vivado =>
          // Ensure that a board is provided
          if (args.board.isEmpty) {
            throw new IllegalArgumentException("No board provided for Vivado synthesis target. Please provide a board using the --board argument.")
          }
          log.info(s"Targeting Vivado synthesis for board ${args.board.get}")
          val synPaths = new BoardSOCTPaths(args, config)
          config.params = config.params.orElse(new WithSOCTPaths(synPaths))
          generateVivadoDesign(args, synPaths, config)
        case Targets.Yosys =>
          log.info("Targeting Yosys synthesis")
          val synPaths = new YosysSOCTPaths(args, config)
          config.params = config.params.orElse(new WithSOCTPaths(synPaths))
          generateYosysDesign(args, synPaths, config)
      }
    case None => // arguments are bad, error message will have been displayed
  }
}
