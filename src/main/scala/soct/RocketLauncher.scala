package soct

import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization.writePretty
import org.json4s.{DefaultFormats, Formats}
import scopt.OptionParser

import java.nio.file.{Files, Path, Paths}
import scala.io.{BufferedSource, Source}
import scala.jdk.CollectionConverters.MapHasAsJava

object RocketLauncher {

  implicit val formats: Formats = DefaultFormats + PathSerializer

  // Default parameters for the launcher
  private val logLevels = Seq("debug", "info", "warn", "error")

  sealed trait Targets {
    def name: String
  }

  private object Targets {
    case object Vivado extends Targets {
      val name: String = "vivado"
    }

    case object Yosys extends Targets {
      val name: String = "yosys"
    }

    case object Verilator extends Targets {
      val name: String = "verilator"
    }

    val values: Seq[Targets] = Seq(Vivado, Yosys, Verilator)

    def fromString(s: String): Option[Targets] = values.find(_.name == s.toLowerCase)

    def parse(s: String): Targets =
      fromString(s).getOrElse(throw new IllegalArgumentException(s"Invalid target: $s. Allowed: ${values.map(_.name).mkString(", ")}"))
  }

  case class LauncherArgs(
                           // General options
                           workspaceDir: Path = Utils.projectRoot().resolve("workspace"),
                           baseConfig: String = "soct.RocketB1",
                           xlen: Int = 64,
                           logLevel: String = logLevels(1), // info
                           useRocketCFiles: Boolean = false,
                           singleVerilogFile: Boolean = false,
                           mabi32: String = "ilp32",
                           mabi64: String = "lp64",
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
                           target: Targets = Targets.Verilator,
                           // Simulation options
                           simTop: String = "soct.SoctSimTop",
                           overrideSimFiles: Boolean = true,
                           // Synthesis options
                           synTop: String = "soct.RocketSystem",
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


  private val parser = new OptionParser[LauncherArgs]("RocketLauncher") {
    help("help").text("Prints this usage text")
    // General options
    opt[String]('d', "dir").action((x, c) => c.copy(workspaceDir = Paths.get(x).toAbsolutePath)).text(s"The directory to store the generated files. Default is ${LauncherArgs().workspaceDir}.")
    opt[String]('c', "base-config").action((x, c) => c.copy(baseConfig = x)).text(s"The base config to build - can include additional configs (i.e. Parameters) to add parts to the system. Comma separated list that. Default is ${LauncherArgs().baseConfig}.")
    opt[Int]("xlen").action((x, c) => c.copy(xlen = x)).text(s"The xlen to use. Default is ${LauncherArgs().xlen}.")
    opt[String]("log-level")
      .action((x, c) => c.copy(logLevel = x))
      .validate(x =>
        if (logLevels.contains(x.toLowerCase)) success
        else failure(s"Invalid log level. Allowed: ${logLevels.mkString(", ")}")
      )
      .text(s"The log level to use. Options: ${logLevels.mkString(", ")}. Default is ${LauncherArgs().logLevel}.")
    opt[Unit]("use-rocket-c-files").action((_, c) => c.copy(useRocketCFiles = true)).text(s"DO NOT delete the *.cc files that are emitted by addResource. Only add this flag if you want to use Chipyard and you know what you're doing.")
    opt[Unit]("single-verilog-file").action((_, c) => c.copy(singleVerilogFile = true)).text(s"(Ignored for Chisel 3 compiler - it always outputs a single file) Generate a single verilog file instead of splitting it up into modules. Due to the way firtool handles things, this flag DISABLES ANY FORM OF VERIFICATION INCLUDING PRINTF. When emitting a verilog for a board, this flag is always enabled as verification is not possible anyway, it is only relevant for simulation.")
    opt[String]("mabi32").action((x, c) => c.copy(mabi32 = x)).text(s"The mabi to use for 32 bit bootrom. Default is ${LauncherArgs().mabi32}.")
    opt[String]("mabi64").action((x, c) => c.copy(mabi64 = x)).text(s"The mabi to use for 64 bit bootrom. Default is ${LauncherArgs().mabi64}.")
    // Firtool options
    opt[String]("firtool-path").action((x, c) => c.copy(firtoolPath = Some(Paths.get(x)))).text(s"The path to the firtool binary. Overrides the version. If not set, the version together with the firtool resolver will be used.")
    opt[String]("firtool-version").action((x, c) => c.copy(firtoolVersion = x)).text(s"The version of firtool to use. Only change if you encounter issues. Default is ${LauncherArgs().firtoolVersion}.")
    opt[String]('a', "firtool-arg").unbounded().action((x, c) => c.copy(firtoolArgs = c.firtoolArgs :+ x)).text(s"Additional arguments to pass to firtool. Is only applied in the last lowering stage. Can be used multiple times.")
    // Simulation options
    opt[Unit]("no-override-sim-files").action((_, c) => c.copy(overrideSimFiles = false)).text(s"When generating a design to be used with simulation, DO NOT copy, and potentially overwrite the files to the simulation directory - Only keep them in the workspace directory.")
    opt[String]("sim-top").action((x, c) => c.copy(simTop = x)).text(s"The top module to use for simulation. Default is ${LauncherArgs().simTop}.")
    // Synthesis options
    opt[String]("syn-top").action((x, c) => c.copy(synTop = x)).text(s"The top module to use for synthesis. Default is ${LauncherArgs().synTop}.")
    opt[String]('t', "target").action((x, c) => c.copy(target = Targets.parse(x))).text(s"Whether to simulate or synthesize the design using various backends. Default is ${LauncherArgs().target}.")
    // Vivado specific options
    opt[String]("vivado-settings").action((x, c) => c.copy(vivadoSettings = Some(Paths.get(x)))).text(s"The vivado settings file to run before executing vivado. Default is ${LauncherArgs().vivadoSettings}.")
    opt[String]("vivado").action((x, c) => c.copy(vivado = Some(Paths.get(x)))).text(s"The vivado executable script to use. Default is ${LauncherArgs().vivado}.")
    // Yosys specific options
    // TODO add yosys options here
    // Board specific options
    opt[String]('b', "board").action((x, c) => c.copy(board = Some(x))).text(s"The targeted board for the synthesis targets. See syn/boards for supported boards.")
    opt[Double]('f', "freq-mhz").action((x, c) => c.copy(freqMHz = Some(x))).text("The frequency of the system in MHz. Required for some synthesis targets.")
    // Terminating options
    opt[Unit]("version").action((_, c) => c.copy(getVersion = true)).text("Prints the version of the tool.")
    opt[Unit]("wtf").action((_, c) => c.copy(wtf = true)).text("What the firtool -- Prints the firtool help.")
  }

  // The path to the firtool binary, is set in main
  private var firtoolPath = Option.empty[Path]

  // The current SocPaths, is set in main
  var currentSoCPaths: Option[SocPaths] = None

  abstract class SocPaths(args: LauncherArgs) {
    val scriptsDir: Path = Utils.projectRoot().resolve("scripts").resolve("build")
    val rocketChipDir: Path = if (chisel3.BuildInfo.version.startsWith("3.")) {
      Utils.projectRoot().resolve("generators").resolve("rocket-chip-chisel3")
    } else {
      Utils.projectRoot().resolve("generators").resolve("rocket-chip")
    }
    val rocketBootrom: Path = rocketChipDir.resolve("bootrom").resolve("bootrom.img")
    val systemName: String = "riscv_system"
    val systemDir: Path
    val bootromScript: Path

    def firtoolBinary: Path = firtoolPath.getOrElse(throw new RuntimeException("Firtool path not set."))

    def verilogFile: Path = systemDir.resolve(s"$systemName.v")

    def lowFirrtlFile: Path = systemDir.resolve(s"$systemName.opt.lo.fir") // Optimized low firrtl, only when using chisel

    def firrtlFile: Path = systemDir.resolve(s"$systemName.fir")

    def annoFile: Path = systemDir.resolve(s"$systemName.anno.json")

    def dtsFile: Path = systemDir.resolve(s"$systemName.dts")

    def dtbFile: Path = systemDir.resolve(s"$systemName.dtb")

    // Custom to string for easier debugging (thanks ChatGPT)
    override def toString: String = {
      s"""
         |SocPaths:
         |  scriptsDir: $scriptsDir
         |  rocketChipDir: $rocketChipDir
         |  rocketBootrom: $rocketBootrom
         |  systemName: $systemName
         |  systemDir: $systemDir
         |  bootromScript: $bootromScript
         |  firtoolBinary: $firtoolBinary
         |  verilogFile: $verilogFile
         |  lowFirrtlFile: $lowFirrtlFile
         |  firrtlFile: $firrtlFile
         |  annoFile: $annoFile
         |  dtsFile: $dtsFile
         |  dtbFile: $dtbFile
         |""".stripMargin
    }
  }

  private class YosysSocPaths(args: LauncherArgs, config: Config) extends SocPaths(args) {
    // For example: workspace/RocketB1-64/system-yosys
    val systemDir: Path = args.workspaceDir.resolve(config.configFull).resolve("system-yosys")
    val bootromScript: Path = scriptsDir.resolve(s"build-sim-boot.${config.scriptEnding}") // FIXME: Yosys bootrom script
  }

  private class BoardSocPaths(args: LauncherArgs, config: Config) extends SocPaths(args) {
    // For example: workspace/RocketB1-64/system-zcu104
    val boardDts: Path = Paths.get(s"${Utils.boardsPath()}/${args.board.get}/bootrom.dts")
    val boardParams: Path = Paths.get(s"${Utils.boardsPath()}/${args.board.get}/params.json")
    val systemDir: Path = args.workspaceDir.resolve(config.configFull).resolve(s"system-${args.board.get}")
    val bootromScript: Path = scriptsDir.resolve(s"build-sd-boot.${config.scriptEnding}")
  }

  private class SimSocPaths(args: LauncherArgs, config: Config) extends SocPaths(args) {
    // For example: workspace/RocketB1-64/sim
    val systemDir: Path = args.workspaceDir.resolve(config.configFull).resolve("sim")
    val bootromScript: Path = scriptsDir.resolve(s"build-sim-boot.${config.scriptEnding}")
  }

  case class Config(
                     args: LauncherArgs,
                     mabi: String,
                     var configs: Seq[String],
                     config: String,
                     configFull: String,
                     scriptEnding: String,
                     rvPrefix: String
                   )

  object Config {
    def apply(args: LauncherArgs): Config = {
      val mabi = if (args.xlen == 32) args.mabi32 else args.mabi64
      val configs = args.baseConfig.split(',').map(_.trim).toSeq
      val config = configs.head.split('.').last
      val configFull = s"${config}-${args.xlen}"
      val scriptEnding = if (Utils.isWindows) "bat" else "sh"
      val rvPrefix = Utils.installRiscVToolchain()
      new Config(args, mabi, configs, config, configFull, scriptEnding, rvPrefix)
    }
  }


  // Implemented in scala-unmanaged (src/main/scala-unmanaged) based on the chisel version that is used
  trait transpiles {
    def evalDesign(top: String, configs: Seq[String], paths: SocPaths, bootromPath: Path, logLevel: String, useCirct: Boolean): Set[Path]

    def emitLowFirrtl(c: RocketLauncher.Config, paths: SocPaths): Unit

    def emitVerilog(c: RocketLauncher.Config, paths: SocPaths, firtoolPlugins: Seq[Path]): Unit
  }


  private def compileBootrom(paths: SocPaths, artifacts: Set[Path], config: Config,
                             boardDTS: Option[String] = None, boardParams: Option[BoardParams] = None): Unit = {
    val rocketDTS = artifacts.find(_.getFileName.toString.endsWith(".dts")).getOrElse {
      throw new RuntimeException("No dts file found in artifacts")
    }
    var fullDTS = Files.readAllLines(rocketDTS).toArray.mkString("\n")
    if (boardDTS.isDefined && boardParams.isDefined) {
      fullDTS = DTSModifier.modifyDTS(s"$fullDTS\n${boardDTS.get}", boardParams.get)
    }

    Files.write(paths.dtsFile, fullDTS.getBytes)
    // Obtain the architecture from the dts
    val march = DTSExtractor.extractMarch(fullDTS)

    // Compile bootrom
    val env = Map(
      "CROSS_COMPILE" -> config.rvPrefix,
      "MARCH" -> march,
      "MABI" -> config.mabi,
      "DTS_PATH" -> paths.dtsFile.toString,
      "DTB_PATH" -> paths.dtbFile.toString,
      "OUT_DIR" -> paths.systemDir.toString,
    )
    val cmd = new ProcessBuilder(paths.bootromScript.toString)
    cmd.environment().putAll(env.asJava)
    val process = cmd.start()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
      // Dump the error output
      val errorOutput = Source.fromInputStream(process.getErrorStream).getLines().mkString("\n")
      log.error(s"Bootrom build failed with exit code $exitCode. Error output:\n$errorOutput")
      throw new RuntimeException(s"Bootrom build failed with exit code $exitCode")
    }
  }

  // Generate the design for Vivado synthesis
  private def generateVivadoDesign(args: LauncherArgs, boardPaths: BoardSocPaths, config: Config): Unit = {
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
    config.configs :+= "soct.RocketBaseConfig"

    if (Utils.rmrfOpt(boardPaths.systemDir) > 0) {
      log.info(s"Removed existing files in ${boardPaths.systemDir}")
    }
    val tmpDir = boardPaths.systemDir.resolve("tmp")
    tmpDir.toFile.mkdirs()

    // Store these results in a temporary dir - they are only required to generate a dts of the system
    val tmpArtifacts = Transpiler.evalDesign(args.synTop, config, boardPaths, boardPaths.rocketBootrom)

    val boardDts = boardDtsFile.getLines().mkString("\n")

    compileBootrom(boardPaths, tmpArtifacts, config, Some(boardDts), Some(boardParams))

    val bootromImg = boardPaths.systemDir.resolve("bootrom.img")
    assert(bootromImg.toFile.exists(), "Bootrom image not found")

    Utils.rmrfOpt(tmpDir)

    Transpiler.evalDesign(args.synTop, config, boardPaths, bootromImg)

    Transpiler.emitLowFirrtl(config, boardPaths)

    val vivadoConfig = config.copy(args = config.args.copy(singleVerilogFile = true)) // Always generate single verilog file for boards
    Transpiler.emitVerilog(vivadoConfig, boardPaths, args.firtoolArgs)

    if (!chisel3.BuildInfo.version.startsWith("3.")) {
      log.warn("Vivado synthesis is not yet tested with this Chisel version - expect issues.")
    }
    val tclFile = Vivado.generateTCLScript(boardPaths.systemDir, args.board.get, config.config, boardParams)
    Vivado.wrapVHDL(boardPaths.systemDir, Utils.findVerilator(), boardPaths.verilogFile, config.config)
    if (args.vivado.isDefined) {
      Vivado.generateProject(tclFile, args.vivado.get, args.vivadoSettings)
    } else {
      log.warn("No vivado path file provided. Not generating bitstream.")
    }
  }

  // Generate the design for Yosys synthesis
  private def generateYosysDesign(args: LauncherArgs, yosysPaths: YosysSocPaths, config: Config): Unit = {
    log.info("Generating design for Yosys synthesis")
    log.debug(s"Using the following paths: ${yosysPaths.toString}")

    if (args.xlen == 32) {
      config.configs +:= "freechips.rocketchip.rocket.WithRV32" // Prepend, so it is actually used
      config.configs :+= "soct.ExtMem32Bit"
    } else {
      config.configs :+= "soct.ExtMem64Bit"
    }
    config.configs :+= "soct.RocketBaseConfig"

    if (Utils.rmrfOpt(yosysPaths.systemDir) > 0) {
      log.info(s"Removed existing files in ${yosysPaths.systemDir}")
    }
    val tmpDir = yosysPaths.systemDir.resolve("tmp")
    tmpDir.toFile.mkdirs()

    // Store these results in a temporary dir - they are only required to generate a dts of the system
    val tmpArtifacts = Transpiler.evalDesign(args.synTop, config, yosysPaths, yosysPaths.rocketBootrom)

    compileBootrom(yosysPaths, tmpArtifacts, config)

    val bootromImg = yosysPaths.systemDir.resolve("bootrom.img")
    assert(bootromImg.toFile.exists(), "Bootrom image not found")

    Utils.rmrfOpt(tmpDir)

    Transpiler.evalDesign(args.synTop, config, yosysPaths, bootromImg)

    Transpiler.emitLowFirrtl(config, yosysPaths)

    val yosysConfig = config.copy(args = config.args.copy(singleVerilogFile = true)) // Always generate single verilog file for yosys
    Transpiler.emitVerilog(yosysConfig, yosysPaths, args.firtoolArgs)
  }

  // Generate the design for simulation
  private def generateSimDesign(args: LauncherArgs, simPaths: SimSocPaths, config: Config): Unit = {
    log.info("Generating design for simulation")
    log.debug(s"Using the following paths: ${simPaths.toString}")

    config.configs :+= "soct.RocketSimBaseConfig"

    if (args.xlen == 32) {
      config.configs +:= "freechips.rocketchip.rocket.WithRV32" // Comes first, so it is actually used
    }

    if (Utils.rmrfOpt(simPaths.systemDir) > 0) {
      log.info(s"Removed existing files in ${simPaths.systemDir}")
    }
    val tmpDir = simPaths.systemDir.resolve("tmp")
    tmpDir.toFile.mkdirs()

    // Store these results in a temporary dir - they are only required to generate a dts of the system
    val tmpArtifacts = Transpiler.evalDesign(args.simTop, config, simPaths, simPaths.rocketBootrom)

    compileBootrom(simPaths, tmpArtifacts, config)

    val bootromImg = simPaths.systemDir.resolve("bootrom.img")
    assert(bootromImg.toFile.exists(), "Bootrom image not found")

    Utils.rmrfOpt(tmpDir)

    Transpiler.evalDesign(args.simTop, config, simPaths, bootromImg)

    Transpiler.emitLowFirrtl(config, simPaths)

    Transpiler.emitVerilog(config, simPaths, args.firtoolArgs)

    if (!args.useRocketCFiles) {
      val ccFiles = Utils.listFilesWithExtension(simPaths.systemDir, ".cc")
      // Print the files that are going to be deleted
      ccFiles.foreach(f => log.info(s"Deleting ${f.getName}"))
      // and delete them
      ccFiles.foreach(_.delete())
    }

    if (args.overrideSimFiles) {
      val configsSimDir = Utils.projectRoot().resolve("sim").resolve("configs")
      if (!configsSimDir.toFile.exists()) {
        configsSimDir.toFile.mkdirs()
      }
      val configsSimDirConfig = configsSimDir.resolve(config.configFull)
      if (Utils.rmrfOpt(configsSimDirConfig) > 0) {
        log.info(s"Removed existing files in $configsSimDirConfig")
      }
      Utils.recCopy(simPaths.systemDir, configsSimDirConfig)
      log.info(s"Copied files to $configsSimDirConfig")
    }
  }

  def main(args: Array[String]): Unit = parser.parse(args, LauncherArgs()) match {
    case Some(args) =>
      // Set the log level of the logger
      configureLogging(args.logLevel.toUpperCase) // set just the `soct` package

      // First check the terminating options
      if (args.getVersion) {
        throw new NotImplementedError("Version not implemented yet")
      }

      firtoolPath = Some(args.firtoolPath.getOrElse(Utils.findFirtool(args.firtoolVersion)))

      if (args.wtf) {
        Utils.printFirtoolHelp(firtoolPath.get.toString)
        return
      }

      val config = Config(args)
      val prettyConfig = writePretty(config).replace("\"", "").replace(",", "").replace("{", "").replace("}", "").replace(" : ", ": ").replace("\n  ", "\n").replace("\n\n", "\n-------------\n")
      log.info(s"Generating design with the following configuration:$prettyConfig")

      args.target match {
        case Targets.Verilator =>
          log.info("Targeting Verilator simulation")
          val simPaths = new SimSocPaths(args, config)
          currentSoCPaths = Some(simPaths)
          generateSimDesign(args, simPaths, config)
        case Targets.Vivado =>
          // Ensure that a board is provided
          if (args.board.isEmpty) {
            throw new IllegalArgumentException("No board provided for Vivado synthesis target. Please provide a board using the --board argument.")
          }
          log.info(s"Targeting Vivado synthesis for board ${args.board.get}")
          val synPaths = new BoardSocPaths(args, config)
          currentSoCPaths = Some(synPaths)
          generateVivadoDesign(args, synPaths, config)
        case Targets.Yosys =>
          log.info("Targeting Yosys synthesis")
          val synPaths = new YosysSocPaths(args, config)
          currentSoCPaths = Some(synPaths)
          generateYosysDesign(args, synPaths, config)
      }
    case None => // arguments are bad, error message will have been displayed
  }
}
