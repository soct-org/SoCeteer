package soct

import org.chipsalliance.cde.config.Parameters
import org.json4s.{DefaultFormats, Formats}
import soct.system.vivado.SOCTVivado

import scala.reflect.io.Path.jfile2path

object SOCTLauncher {

  // JSON formats for serializing/deserializing
  implicit val formats: Formats = DefaultFormats + PathSerializer + TargetsSerializer

  /**
   * Configuration for the SOCT design generation
   *
   * @param args          The parsed SOCT arguments
   * @param mabi          The RISC-V ABI to use for compiling the bootrom
   * @param topModule     The top module to instantiate
   * @param topModuleName The name of the top module
   * @param params        The Parameters to use for the design generation
   * @param configName    The name of the configuration (used for output directories)
   */
  case class SOCTConfig(
                         args: SOCTArgs,
                         mabi: String,
                         topModule: ChiselTop,
                         topModuleName: String,
                         var params: Parameters,
                         configName: String,
                       )

  object SOCTConfig {
    def apply(args: SOCTArgs): SOCTConfig = {
      val mabi = args.userMabi.getOrElse(if (args.xlen == 32) "ilp32" else "lp64")
      val params = new WithHartBootFreqMHz(args.freqsMHz) ++ args.baseConfig
      val topModule = args.userTop.getOrElse(args.target.defaultTop)
      val topModuleName = topModule.fold(_.getSimpleName, _.getSimpleName)
      val configName = s"${args.baseConfig.getClass.getSimpleName}-${args.xlen}"
      new SOCTConfig(args, mabi, topModule, topModuleName, params, configName)
    }
  }

  // Generate the design for Vivado synthesis
  private def generateVivadoDesign(args: SOCTArgs, boardPaths: VivadoSOCTPaths, config: SOCTConfig): Unit = {
    log.info("Generating design for Vivado synthesis")
    log.debug(s"Using the following paths: ${boardPaths.toString}")

    if (args.xlen == 32) {
      config.params = config.params.orElse(new ExtMem32Bit)
    } else {
      config.params = config.params.orElse(new ExtMem64Bit)
    }
    if (args.board.isEmpty) {
      throw new IllegalArgumentException("No board provided for Vivado synthesis target. Please provide a board using the --board argument.")
    }
    config.params = config.params.orElse(new WithXilinxFPGA(args.board.get))
    config.params = config.params.orElse(new soct.RocketVivadoBaseConfig)

    Transpiler.evalDesign(config, boardPaths)

    Transpiler.emitLowFirrtl(config, boardPaths)

    Transpiler.emitVerilog(config, boardPaths)

    val success = SOCTVivado.prepareForVivado(boardPaths, config)

    if (args.vivado.isEmpty) {
      log.warn("No Vivado path provided, cannot override existing Vivado project.")
    } else if (success) {
      boardPaths.vivadoProjectDir.toFile.mkdirs()
      SOCTVivado.generateProject(boardPaths.tclInitFile, args.vivado.get, boardPaths.vivadoProjectDir)
    } else {
      log.warn("No Vivado project generated due to errors in design generation.")
    }
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

    Transpiler.evalDesign(config, simPaths)

    Transpiler.emitLowFirrtl(config, simPaths)

    Transpiler.emitVerilog(config, simPaths)

    if (args.overrideSimFiles) {
      val configsSimDir = SOCTPaths.get("sim-configs")
      if (!configsSimDir.toFile.exists()) {
        configsSimDir.toFile.mkdirs()
      }
      val simConfigDir = configsSimDir.resolve(config.configName)
      if (simConfigDir.toFile.exists()) {
        simConfigDir.toFile.deleteRecursively()
        log.info(s"Removed existing files in $simConfigDir")
      }
      // Copy systemDir to simConfigDir recursively using Scala api:
      SOCTUtils.recCopy(simPaths.systemDir, simConfigDir)
      log.info(s"Copied files to $simConfigDir")
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

      val paths: SOCTPaths = args.target match {
        case Targets.Verilator =>
          new SimSOCTPaths(args, config)
        case Targets.Vivado =>
          new VivadoSOCTPaths(args, config)
        case Targets.Yosys =>
          new YosysSOCTPaths(args, config)
      }

      config.params = config.params.orElse(new WithSOCTPaths(paths))

      if (paths.systemDir.toFile.exists()) {
        paths.systemDir.toFile.deleteRecursively()
        log.info(s"Removed existing files in ${paths.systemDir}")
      }

      // Create the Verilog source directory - needed for all targets
      paths.verilogSrc.toFile.mkdirs()

      args.target match {
        case Targets.Verilator =>
          log.info("Targeting Verilator simulation")
          generateSimDesign(args, paths.asInstanceOf[SimSOCTPaths], config)
        case Targets.Vivado =>
          log.info(s"Targeting Vivado synthesis for board ${args.board.get}")
          generateVivadoDesign(args, paths.asInstanceOf[VivadoSOCTPaths], config)
        case Targets.Yosys =>
          log.info("Targeting Yosys synthesis")
          generateYosysDesign(args, paths.asInstanceOf[YosysSOCTPaths], config)
      }

    case None => // arguments are bad, error message will have been displayed
  }
}
