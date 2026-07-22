package soct

import chisel3.Module
import scopt.OptionParser

import java.nio.file.{Path, Paths}
import org.chipsalliance.cde.config
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import soct.system.sim.SOCTSimSystem
import soct.system.vivado.SOCTVivadoSystem
import soct.system.vivado.fpga.{PartRegistry, FPGA, FPGARegistry}
import soct.system.yosys.SOCTYosysSystem
import freechips.rocketchip.subsystem.WithPeripheryBusFrequency
import soct.SOCTFreq._
import soct.SOCTNames.{LATEST_SOCT_SYSTEM_CMAKE_FILE, SOCT_SYSTEM_CMAKE_FILE}
import soct.SOCTPaths.projectRoot

/**
 * How far the Vivado flow is driven past project/block-design creation. Carried by the
 * [[VivadoTarget]] family, so the stage is selected by choosing the target
 * (`vivado.syn`, `vivado.bs`) rather than a separate flag; it is orthogonal to the design's
 * core count. `None` (plain `vivado` / `vivado.bd`) means generate the project only.
 */
sealed trait BuildStage {
  /** Human-readable name used in logs and the launch banner. */
  val name: String
}

object BuildStage {
  /** Run synthesis only (`launch_runs synth_1`). */
  case object Synthesis extends BuildStage {
    val name: String = "synthesis"
  }

  /** Run synthesis, implementation and `write_bitstream` (`launch_runs impl_1 -to_step write_bitstream`). */
  case object Bitstream extends BuildStage {
    val name: String = "bitstream"
  }
}


// Define the supported targets
sealed trait Targets {
  /**
   * The name of the target as used on the command line
   */
  val name: String

  /**
   * The canonical backend name written into the generated system file (`SOCT_TARGET`, a compile
   * def the binaries' CMake tests against). Defaults to [[name]]; target families that only
   * differ in post-generation behaviour (e.g. the Vivado stages) override it so the generated
   * design is identical regardless of the chosen variant.
   */
  def systemName: String = name

  /**
   * The default bootrom path relative to the "binaries" directory - used for --build when invoking CMake
   */
  val defaultBootrom: String

  /**
   * The default top module class for this target
   */
  val defaultTop: ChiselTop

  /** A one-line description shown in the `--target` help listing. */
  val description: String
}

/**
 * The Vivado target family. All variants emit the same design (block-design builder, SD/UART,
 * `sd-boot`); they differ only in how far the generated flow is driven ([[buildStage]]).
 * Match on `case _: VivadoTarget` wherever the Vivado backend is meant, so every variant is
 * handled uniformly.
 */
sealed trait VivadoTarget extends Targets {
  override def systemName: String = "vivado"
  val defaultBootrom: String = "sd-boot"
  val defaultTop: ChiselTop = Right(classOf[SOCTVivadoSystem])

  /** The stage to build automatically after project generation; `None` = generate the project only. */
  val buildStage: Option[BuildStage]
}

/**
 * The Verilator simulation target family. All variants emit the same design and report the
 * canonical `SOCT_TARGET` name `verilator`; they differ only in whether the C++ simulator is
 * built automatically after emission ([[build]]). Match on `case _: VerilatorTarget` for the
 * simulation backend.
 */
sealed trait VerilatorTarget extends Targets {
  override def systemName: String = "verilator"
  val defaultBootrom: String = "testchipip-boot"
  val defaultTop: ChiselTop = Left(classOf[SOCTSimSystem])

  /** Whether to configure and build the `sim` CMake project after emitting the design. */
  val build: Boolean
}

object Targets {
  /**
   * Legacy Vivado target: generate the project and block design only (kept for backwards
   * compatibility - identical to `vivado.bd`). Use `vivado.syn`/`vivado.bs` to also build.
   */
  case object Vivado extends VivadoTarget {
    val name: String = "vivado"
    val buildStage: Option[BuildStage] = None
    val description: String = "Vivado project + block design, no build (legacy alias of vivado.bd)."
  }

  /** Vivado: generate the project and block design only (explicit spelling of the legacy `vivado`). */
  case object VivadoBd extends VivadoTarget {
    val name: String = "vivado.bd"
    val buildStage: Option[BuildStage] = None
    val description: String = "Vivado project + block design only (open it in the GUI to build)."
  }

  /** Vivado: generate the project, then automatically launch synthesis (detached). */
  case object VivadoSyn extends VivadoTarget {
    val name: String = "vivado.syn"
    val buildStage: Option[BuildStage] = Some(BuildStage.Synthesis)
    val description: String = "Generate the project, then run synthesis (detached; see --vivado-parallel)."
  }

  /** Vivado: generate the project, then automatically build the bitstream (detached). */
  case object VivadoBs extends VivadoTarget {
    val name: String = "vivado.bs"
    val buildStage: Option[BuildStage] = Some(BuildStage.Bitstream)
    val description: String = "Generate the project, then build the bitstream (detached; see --vivado-parallel)."
  }

  /**
   * Yosys target for synthesis
   */
  case object Yosys extends Targets {
    val name: String = "yosys"
    val defaultBootrom: String = "sd-boot"
    val defaultTop: ChiselTop = Right(classOf[SOCTYosysSystem])
    val description: String = "Emit the design for Yosys synthesis."
  }

  /**
   * Verilator: emit the simulation design, and optionally build the C++ simulator afterwards.
   * `verilator` emits only (the default); `verilator.build` also configures and builds the `sim`
   * CMake project. All variants report the canonical `SOCT_TARGET` name `verilator`.
   */
  case object Verilator extends VerilatorTarget {
    val name: String = "verilator"
    val build: Boolean = false
    val description: String = "Emit the design for Verilator simulation (build the simulator yourself)."
  }

  /** Verilator: emit the design, then configure and build the simulator with CMake. */
  case object VerilatorBuild extends VerilatorTarget {
    val name: String = "verilator.build"
    val build: Boolean = true
    val description: String = "Emit the design, then configure and build the C++ simulator with CMake."
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
  val values: Seq[Targets] = Seq(Vivado, VivadoBd, VivadoSyn, VivadoBs, Yosys, Verilator, VerilatorBuild)

  private def fromString(s: String): Option[Targets] = values.find(_.name == s.toLowerCase)
}


/**
 * All launcher arguments with their defaults; parsed from the command line by [[SOCTParser]].
 * Field groups mirror the CLI help: general, firtool, Vivado, remote development, terminating.
 */
case class SOCTArgs(
                     // General options
                     workspaceDir: Path = projectRoot.resolve("workspace"),
                     userOutDir: Option[Path] = None,
                     baseConfig: config.Parameters = new RocketB1,
                     extraConfigs: Seq[String] = Seq.empty,
                     xlen: Int = 64,
                     logLevel: String = logLevels(1), // info
                     verboseChisel: Boolean = false,
                     singleVerilogFile: Boolean = false,
                     includeLocationInfo: Boolean = false,
                     target: Targets = Targets.Verilator,
                     emitLatestSOCTSystem: Boolean = true,
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

                     // Vivado specific options
                     vivado: Option[Path] = None,
                     board: Option[FPGA] = None,
                     coreFreq: Option[Freq] = Some(100.MHz),
                     peripheryFreq: Freq = 100.MHz,
                     overrideVivadoProject: Boolean = true,
                     extMemParts: Seq[String] = Seq.empty,
                     fastPnR: Boolean = false,
                     // Parallel Vivado jobs for an automatic synthesis/bitstream build (launch_runs -jobs /
                     // general.maxThreads). Only used by the vivado.syn/vivado.bs targets. The stage itself
                     // is the target's buildStage, not a separate field.
                     vivadoParallel: Int = 1,

                     // Remote development
                     remoteDir: Option[Path] = None, // Relative to the remote user home directory.
                     openSSHConfig: Option[String] = None, // The name of the SSH config to use for remote development. This is used to determine the remote host and user.
                     useRemoteVivado: Boolean = false, // Whether to use Vivado on the remote machine for synthesis (i.e. the path to Vivado provided by --vivado is on the remote machine and not the local machine)

                     // Terminating options
                     syncFromRemote: Boolean = false, // Whether to sync the remote directory to the local workspace directory
                     getVersion: Boolean = false, // Print the version of the tool
                     wtf: Boolean = false, // What the firtool - for debugging
                   )

object SOCTParser extends OptionParser[SOCTArgs]("SOCTLauncher") {

  /**
   * Render a "default determined by the target" help listing, collapsing targets that share the
   * same value (e.g. the Vivado family all map to the same bootrom/top) into one `a/b -> value`
   * group, in target declaration order.
   */
  private def defaultsByTarget[A](f: Targets => A): String =
    Targets.values.groupBy(f).toSeq
      .sortBy { case (_, ts) => Targets.values.indexOf(ts.head) }
      .map { case (value, ts) => s"${ts.map(_.name).mkString("/")} -> $value" }
      .mkString(", ")

  /**
   * Word-wrap help text to `width` columns so entries render as a few readable lines instead of
   * one very long line. Explicit newlines in the input are kept as paragraph breaks.
   */
  private def wrap(text: String, width: Int = 96): String =
    text.split("\n", -1).map { para =>
      para.split(" ").filter(_.nonEmpty).foldLeft(Vector("")) { (lines, word) =>
        val last = lines.last
        if (last.isEmpty) lines.init :+ word
        else if (last.length + 1 + word.length <= width) lines.init :+ s"$last $word"
        else lines :+ word
      }.mkString("\n")
    }.mkString("\n")

  /**
   * Modify the args based on the target
   *
   * @param args   The original args
   * @param target The target to modify for
   * @return The modified args
   */
  def modifyArgsBasedOnTarget(args: SOCTArgs, target: Targets): SOCTArgs = {
    target match {
      case _: VerilatorTarget =>
        args
      case _: VivadoTarget =>
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
  opt[String]('w', "workspace").action((x, c) => c.copy(workspaceDir = Paths.get(x).toAbsolutePath)).text(wrap(s"A custom workspace directory to use instead of the default ${defaultSOCTArgs.workspaceDir}. All generated files will be stored in a subdirectory of this directory based on the config and target. Superseded by --out-dir if both are provided."))
  opt[String]('o', "out-dir").action((x, c) => c.copy(userOutDir = Some(Paths.get(x).toAbsolutePath))).text(wrap("The direct output directory to use for all generated files (Not based on target/config). If set, this overrides the default workspace directory and the --workspace option."))
  opt[String]('c', "config")
    .action((x, c) => c.copy(baseConfig = SOCTUtils.instantiateConfig(x)))
    .text(wrap(s"The config that determines what system to build (Rocket-Chip, Boom, Gemmini etc). Default is ${defaultSOCTArgs.baseConfig.getClass.getName}."))
  opt[String]("with-config")
    .unbounded()
    .validate(x =>
      scala.util.Try(SOCTUtils.instantiateConfig(x)) match {
        case scala.util.Success(_) => success
        case scala.util.Failure(e) => failure(e.getMessage)
      }
    )
    .action((x, c) => c.copy(extraConfigs = c.extraConfigs :+ x))
    .text(wrap("Fully qualified name of an additional Config class mixed into the parameters (e.g. soct.WithVideoStream). Can be used multiple times; the leftmost occurrence has the highest priority, and all of them override the --config base. The class must have a zero-argument constructor."))
  opt[Int]("xlen").action((x, c) => c.copy(xlen = x)).text(wrap(s"The xlen to use. Default is ${defaultSOCTArgs.xlen}. Allowed values are 32 and 64 - 32 adds ${classOf[freechips.rocketchip.rocket.WithRV32].getName} to the config."))
  opt[String]("ll")
    .action((x, c) => c.copy(logLevel = x))
    .validate(x =>
      if (logLevels.contains(x.toLowerCase)) success
      else failure(s"Invalid log level. Allowed: ${logLevels.mkString(", ")}")
    )
    .text(s"The log level to use. Options: ${logLevels.mkString(", ")}. Default is ${defaultSOCTArgs.logLevel}.")
  opt[Unit]("verbose-chisel").action((_, c) => c.copy(verboseChisel = true)).text(s"Enable verbose Chisel output during elaboration. Default is ${defaultSOCTArgs.verboseChisel}.")
  opt[Unit]("single-verilog-file").action((_, c) => c.copy(singleVerilogFile = true)).text(wrap("(Ignored for Chisel 3 compiler - it always outputs a single file) Generate a single verilog file instead of splitting it up into modules. Due to the way firtool handles things, this flag DISABLES ANY FORM OF VERIFICATION INCLUDING PRINTF."))
  opt[Unit]("include-location-info").action((_, c) => c.copy(includeLocationInfo = true)).text(wrap("Include location information (file and line number) as comments in the generated verilog/systemverilog file."))
  opt[String]('t', "target").action((x, c) => c.copy(target = Targets.parse(x)))
    .text(
      s"""The backend to emit (and optionally build) the design for. Available targets:
         |${Targets.values.map(t => f"  ${t.name}%-16s ${t.description}").mkString("\n")}
         |Default: ${defaultSOCTArgs.target.name}.""".stripMargin)
  opt[Unit]("no-latest-soct-system").action((_, c) => c.copy(emitLatestSOCTSystem = false)).text(s"Do NOT emit a link to latest emitted $SOCT_SYSTEM_CMAKE_FILE ($LATEST_SOCT_SYSTEM_CMAKE_FILE).")
  opt[String]("bootrom").action((x, c) => c.copy(userBootrom = Some(x))).text(wrap(s"""The path to the bootrom binary to use. Must be relative to the "binaries" directory. Default is determined by the target: ${defaultsByTarget(_.defaultBootrom)}."""))
  opt[String]("top")
    .validate(x =>
      try {
        val cls = Class.forName(x)
        if (classOf[Module].isAssignableFrom(cls) || classOf[LazyModule].isAssignableFrom(cls)) {
          success
        } else {
          failure(s"Invalid top module class: $x. Must extend either chisel3.Module or diplomacy.LazyModule.")
        }
      } catch {
        case _: ClassNotFoundException => failure(s"Top module class not found: $x. Check if the package path is correct and the class is on the classpath.")
      }
    )
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
    .text(wrap(s"The fully qualified name (including the package path) of the top module class to use. Default is determined by the target: ${defaultsByTarget(_.defaultTop.fold(_.getName, _.getName))}."))
  opt[String]("mabi").action((x, c) => c.copy(userMabi = Some(x))).text(s"The machine ABI to use (e.g., ilp32, lp64) to compile the bootrom.")


  // Firtool options
  opt[String]("firtool-path").action((x, c) => c.copy(firtoolPath = Some(Paths.get(x)))).text(s"The path to the firtool binary. Overrides the version. If not set, the version together with the firtool resolver will be used.")
  opt[String]("firtool-version").action((x, c) => c.copy(firtoolVersion = x)).text(s"The version of firtool to use. Only change if you encounter issues. Default is ${defaultSOCTArgs.firtoolVersion}.")
  opt[String]('a', "firtool-arg").unbounded().action((x, c) => c.copy(userFirtoolArgs = c.userFirtoolArgs :+ x)).text(s"Additional arguments to pass to firtool. Is only applied in the last lowering stage. Can be used multiple times.")

  // Vivado specific options
  opt[String]("vivado").action((x, c) => c.copy(vivado = Some(Paths.get(x)))).text(s"The vivado executable script to use. Default is ${defaultSOCTArgs.vivado}.")
  opt[String]('b', "board")
    .action((x, c) => c.copy(board = FPGARegistry.n2bOpt(x)))
    .validate(x =>
      if (FPGARegistry.n2bOpt(x).isDefined) success
      else failure(s"Invalid FPGA board $x. Available boards: ${FPGARegistry.getKnownBoards.mkString(", ")}.")
    )
    .text(s"The FPGA board to target for synthesis. Available boards: ${FPGARegistry.getKnownBoards.mkString(", ")}.")
  opt[String]("core-freq-mhz").action((x, c) => c.copy(coreFreq = Some(x.toDouble.MHz)))
    .text(wrap(
      s"""The frequency to use for the core clock in MHz as a floating point number. This should only be used for simple designs where it is sufficient to set a single frequency for all bus clocks.
         |For more complex designs where the core is driven by multiple clocks, set the frequency of the bus clock(s) by adding the configs ${classOf[WithPeripheryBusFrequency].getName} etc. to the main config.""".stripMargin))
  opt[String]("periphery-freq-mhz").action((x, c) => c.copy(peripheryFreq = x.toDouble.MHz))
    .text(wrap(
      s"""The frequency to use for the periphery bus clock in MHz as a floating point number. This is used for the bus clock(s) that drive the periphery devices like the SDCard controller and UART.
         |Default is ${defaultSOCTArgs.peripheryFreq}.""".stripMargin))
  opt[Unit]("no-override-vivado-project").action((_, c) => c.copy(overrideVivadoProject = false)).text(wrap("When generating a design for synthesis with vivado, DO NOT overwrite an existing vivado project in the out-dir directory."))
  opt[String]("ext-mem-part")
    .unbounded()
    .action((x, c) => c.copy(extMemParts = c.extMemParts :+ x))
    .validate(x => PartRegistry.capacityOf(x) match {
      case Some(_) => success
      case None => failure(s"Unknown DDR4 memory part '$x'. Its capacity could not be derived from the part name. Add the part to PartRegistry (known parts: ${PartRegistry.knownParts.mkString(", ")}).")
    })
    .text(wrap("The Vivado DDR4 memory part name of the DIMM to use (e.g., MTA16ATF2G64HZ-2G3). Can be specified multiple times for multiple external memory ports (first occurrence configures the first external memory port, second occurrence the second one, etc.). The memory capacity is derived from the part name (see PartRegistry - unknown parts must be added there). If omitted, the board's preset part is used. A part differing from the board preset switches the port to a custom (non board-flow) DDR4 interface, which requires the board definition to provide the pin map and clock timing (the ZCU104 does)."))

  opt[Unit]("fast-pnr").action((_, c) => c.copy(fastPnR = true)).text(wrap("Reserved: fast place&route mode. Currently a no-op - no generated logic is simplified; the flag is kept for future PnR-effort tradeoffs."))

  opt[Int]("vivado-parallel").valueName("<jobs>").action((x, c) => c.copy(vivadoParallel = x))
    .text(wrap(s"Number of parallel Vivado jobs (launch_runs -jobs / general.maxThreads) for an automatic build. Only used by the vivado.syn/vivado.bs targets. Default is ${defaultSOCTArgs.vivadoParallel}. The build runs detached: its log path and a follow command are printed. Works locally, or on the remote host with --use-remote-vivado (pull results back afterwards with --sfr)."))

  // Remote development options
  opt[String]("remote-dir").action((x, c) => c.copy(remoteDir = Some(Paths.get(x)))).text(wrap("The directory on the remote machine to use for remote development. This should be a path relative to the remote user home directory. If not set, remote development features will be disabled."))
  opt[String]("ssh-config").action((x, c) => c.copy(openSSHConfig = Some(x))).text(wrap("The name of the SSH config to use for remote development. This is used to determine the remote host and user. If not set, remote development features will be disabled."))
  opt[Unit]("use-remote-vivado").action((_, c) => c.copy(useRemoteVivado = true)).text(wrap("Whether to use Vivado on the remote machine for synthesis (i.e. the path to Vivado provided by --vivado is on the remote machine and not the local machine). Only applicable if --remote-dir and --ssh-config are set."))

  // Terminating options
  opt[Unit]("sfr").action((_, c) => c.copy(syncFromRemote = true)).text(wrap("Sync from remote - Sync the remote directory to the local workspace directory. Only applicable if --remote-dir and --ssh-config are set."))
  opt[Unit]("version").action((_, c) => c.copy(getVersion = true)).text("Prints the version of the tool.")
  opt[Unit]("wtf").action((_, c) => c.copy(wtf = true)).text("What the firtool -- Prints the firtool help.")

  checkConfig { c =>
    if (c.vivadoParallel < 1)
      failure(s"--vivado-parallel must be >= 1 (got ${c.vivadoParallel}).")
    else success
  }
}