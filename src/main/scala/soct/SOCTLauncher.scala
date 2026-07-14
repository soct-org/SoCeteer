package soct

import org.chipsalliance.cde.config.Parameters
import org.json4s.{DefaultFormats, Formats}
import soct.SOCTNames.SOCT_SYSTEM_CMAKE_KEY
import soct.SOCTUtils.configName
import soct.system.vivado.{SOCTVivado, hasMultiMemSupport}

import java.nio.file.Files
import scala.reflect.io.Path.jfile2path
import scala.util.control.NonFatal

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
      var params: Parameters = new WithPeripheryClockSpeed(args.peripheryFreq)
      val topModule = args.userTop.getOrElse(args.target.defaultTop)
      val topModuleName = topModule.fold(_.getSimpleName, _.getSimpleName)

      if (args.coreFreq.isDefined) {
        params ++= new WithSingleBusClockSpeed(args.coreFreq.get)
      }
      if (args.fastPnR) {
        params ++= new soct.WithFastPnR
      }

      if (args.xlen == 32) {
        params = params.alter(new freechips.rocketchip.rocket.WithRV32)
      }

      // Additional config fragments from --with-config: below the launcher's own argument
      // fragments, above the --config base; leftmost occurrence wins among each other.
      val extras = args.extraConfigs.map(SOCTUtils.instantiateConfig)
      val withExtras = extras.foldLeft(params)((acc, extra) => acc ++ extra)
      val finalParams = withExtras ++ args.baseConfig

      // Feature fragments (SOCTFeatureConfig) suffix the system name, so their designs get
      // their own workspace directories instead of overwriting the plain config's.
      val suffixes = finalParams(SOCTNameSuffixes).distinct
      val name = (configName(args.baseConfig, args.xlen) +: suffixes).mkString("-")

      new SOCTConfig(args, mabi, topModule, topModuleName, finalParams, name)
    }
  }

  // Generate the design for Vivado synthesis
  private def generateVivadoDesign(args: SOCTArgs, boardPaths: VivadoSOCTPaths, config: SOCTConfig): Unit = {
    log.info("Generating design for Vivado synthesis")

    if (args.board.isEmpty) {
      throw new IllegalArgumentException("No board provided for Vivado synthesis target. Please provide a board using the --board argument.")
    }

    val memConfig = SOCTMem.genMemConfig(args, hasMultiMemSupport(config.topModule))
    if (memConfig.isDefined) {
      config.params = config.params.orElse(memConfig.get)
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
      SOCTVivado.generateProject(args, boardPaths, config)
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

    config.params = config.params.orElse(new soct.RocketSimBaseConfig)

    Transpiler.evalDesign(config, simPaths)

    Transpiler.emitLowFirrtl(config, simPaths)

    Transpiler.emitVerilog(config, simPaths)
  }

  /**
   * Entry point: parses the arguments, handles the terminating options (--version, --wtf,
   * --sfr), resolves paths and firtool, and dispatches to the selected target's generator.
   *
   * @param raw the raw command-line arguments
   * @throws IllegalArgumentException if required arguments for the selected mode are missing
   *                                  (e.g. --board for vivado, --remote-dir/--ssh-config for --sfr)
   */
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

      if (args.syncFromRemote) {
        if (args.remoteDir.isEmpty) {
          throw new IllegalArgumentException("Remote directory must be provided when using --sync-from-remote")
        }
        if (args.openSSHConfig.isEmpty) {
          throw new IllegalArgumentException("OpenSSH config file must be provided when using --sync-from-remote")
        }
        SOCTRemote.pullDir(args.workspaceDir, args)
        return
      }

      val config = SOCTConfig(args)
      config.params = config.params.orElse(new WithSOCTConfig(config))

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

      paths.createSubdirs()

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

      soct.log.info(s"Design generation complete. Output files can be found in ${paths.systemDir}")

      if (args.emitLatestSOCTSystem) {
        paths.latestSoctSystemCMakeFile.toFile.delete()
        try {
          Files.createSymbolicLink(paths.latestSoctSystemCMakeFile, paths.soctSystemCMakeFile)
        } catch {
          case NonFatal(e) =>
            log.warn(s"Failed to create symbolic link for latest SOCTSystem.cmake file: ${e.getMessage}. This is likely because the operating system does not support symbolic links or the process does not have permission to create them. You can still find the generated SOCTSystem.cmake file at ${paths.soctSystemCMakeFile}")
        }
      }

      soct.log.info(s"${SOCT_SYSTEM_CMAKE_KEY}=${paths.soctSystemCMakeFile}")

    case None => // arguments are bad, error message will have been displayed
  }
}
