package soct

import freechips.rocketchip.devices.tilelink.CLINTKey
import soct.SOCTNames.SOCT_SIMULATOR_EXE

import java.nio.file.{Files, Path}

/**
 * Represents a single CMake variable definition.
 *
 * @param name     The CMake variable name (e.g. "SOCT_ARCH")
 * @param value    The value to set (already rendered as a string)
 * @param comment  A human-readable comment emitted above the set() call
 * @param compileDef If true, this variable is included in SOCT_COMPILE_DEFS list so
 *                   consumers can do target_compile_definitions(foo PRIVATE ${SOCT_COMPILE_DEFS})
 * @param quoted   If true, the compile definition value is wrapped in escaped quotes
 *                 (use for string values; leave false for integers/booleans)
 */
case class CMakeVar(
  name: String,
  value: String,
  comment: String,
  compileDef: Boolean = false,
  quoted: Boolean = false,
)

object CMakeVar {
  /**
   * Render a sequence of CMakeVars into set() calls, then list(APPEND SOCT_COMPILE_DEFS ...) for
   * those with compileDef = true. Using list(APPEND) means each group safely accumulates into the
   * same list regardless of order, and it works even when the list starts empty.
   * Consumers: target_compile_definitions(<target> PUBLIC ${SOCT_COMPILE_DEFS})
   */
  def render(vars: Seq[CMakeVar]): String = {
    val setCalls = vars.map { v =>
      s"""# ${v.comment}
         |set(${v.name} "${v.value}")""".stripMargin
    }.mkString("\n\n")

    val defEntries = vars.filter(_.compileDef).map { v =>
      if (v.quoted) s"""    ${v.name}="$${${v.name}}""""
      else          s"""    ${v.name}=$${${v.name}}"""
    }

    val append =
      if (defEntries.isEmpty) ""
      else
        s"""
           |list(APPEND SOCT_COMPILE_DEFS
           |${defEntries.mkString("\n")}
           |)""".stripMargin

    s"$setCalls$append"
  }
}

object DTSExtractor {
  /**
   * Extract the RISC-V architecture string from a Device Tree Source (DTS) content.
   *
   * @param dts     The content of the DTS file as a string.
   * @param key     The key to search for in the DTS (default is "riscv,isa").
   * @param invalid A sequence of invalid substrings to remove from the extracted architecture string.
   *                Default is "b" which represents big-endian and "_xrocket" which is specific to Rocket cores ("CEASE" instruction).
   * @return
   */
  def extractMarch(dts: String, key: String = "riscv,isa", invalid: Seq[String] = Seq("b", "_xrocket")): String = {
    val pattern = s"""(?s)$key = "(.*?)"""".r
    val matches = pattern.findAllMatchIn(dts).toSeq
    if (matches.isEmpty) {
      throw new IllegalArgumentException(s"Key '$key' not found in DTS")
    }
    var march = matches.head.group(1)
    invalid.foreach(invalidKey => march = march.replaceFirst(invalidKey, ""))
    if (march.isEmpty) {
      throw new IllegalArgumentException(s"Key '$key' not found in DTS")
    }
    march
  }

  def countCPUs(dts: String): Int = {
    val pattern = """cpu@""".r
    pattern.findAllMatchIn(dts).length
  }
}

object SOCTSystemGenerator {

  /**
   * Generate a CMake file that includes important information from the DTS file for building binaries.
   *
   * @param paths  The SOCTPaths containing relevant paths.
   * @param config The SOCTLauncher configuration containing build settings.
   * @return The content of the generated CMake file as a string.
   */
  def generate(paths: SOCTPaths, config: SOCTLauncher.SOCTConfig): String = {

    val dtsContent = Files.readString(paths.dtsFile)
    val march = DTSExtractor.extractMarch(dtsContent)
    val thisDir = "SOCT_SYSTEM_ROOT"
    val realCurrentListDir = paths.soctSystemCMakeFile.getParent.toRealPath()

    def rel(path: Path): String = {
      val currentListDir = paths.soctSystemCMakeFile.getParent
      val suffix = currentListDir.relativize(path).toString.replace("\\", "/")
      s"$${$thisDir}/$suffix".stripSuffix("/")
    }

    def relFromRealBase(path: Path): String = {
      val suffix = realCurrentListDir.relativize(path.toRealPath()).toString.replace("\\", "/")
      s"$${$thisDir}/$suffix".stripSuffix("/")
    }

    // ---------------------------------------------------------------
    // Define all CMake variables here.
    // Set compileDef = true  → variable is added to SOCT_COMPILE_DEFS.
    // Set quoted      = true  → compile definition value is wrapped in quotes (strings).
    // ---------------------------------------------------------------
    val commonVars = Seq(
      CMakeVar("SOCETEER_VERSION",  version,                          "The version of soceteer used to generate this system"),
      CMakeVar("SOCT_CONFIG_NAME",  config.configName,                "The name of the system configuration",  compileDef = true, quoted = true),
      CMakeVar("SOCT_TARGET",       config.args.target.name,          "Whether this system was built for an FPGA board, Verilator simulation etc.", compileDef = true, quoted = true),
      CMakeVar("SOCT_ARCH",         march,                            "The RISC-V architecture string extracted from the DTS", compileDef = true, quoted = true),
      CMakeVar("SOCT_ABI",          config.mabi,                      "The RISC-V ABI to use for compiling binaries for this system", compileDef = true, quoted = true),
      CMakeVar("SOCT_XLEN",         config.args.xlen.toString,        "The XLEN of the system", compileDef = true),
      CMakeVar("SOCT_NCPUS",        DTSExtractor.countCPUs(dtsContent).toString, "The number of CPU cores in the system, extracted from the DTS", compileDef = true),
      CMakeVar("SOCT_VSRCS",        rel(paths.verilogSrcDir),         "The Verilog source files for this system"),
      CMakeVar("SOCT_DTS",          rel(paths.dtsFile),               "The device tree file for this system"),
      CMakeVar("SOCT_DTB",          rel(paths.dtbFile),               "The compiled device tree blob for this system"),
      CMakeVar("SOCT_BOOTROM_IMG",  rel(paths.bootromImgFile),        "The bootrom image for this system"),
      CMakeVar("SOCT_ELFS_DIR",     rel(paths.elfsDir),               "The directory where compiled ELF files for this system are stored"),
      CMakeVar("SOCT_BUILD_DIR",    rel(paths.buildDir),              "A build directory for temporary files during the build process"),
    )

    val preamble =
      s"""# Auto-generated CMake file for SOCT
         |cmake_minimum_required(VERSION 3.20)
         |
         |# The actual path to this CMake file - works even through symlinks
         |file(REAL_PATH "$${CMAKE_CURRENT_LIST_FILE}" _real_file)
         |get_filename_component($thisDir "$${_real_file}" DIRECTORY)
         |
         |# The root directory of the SoCeteer project - all static files are located under this directory
         |cmake_path(SET SOCETEER_ROOT NORMALIZE "${relFromRealBase(SOCTPaths.projectRoot)}")
         |""".stripMargin

    val common = preamble + "\n" + CMakeVar.render(commonVars)

    // Optional variables for specific peripherals
    var optionalVars = Seq.empty[CMakeVar]
    if (config.params(NeedsFatFS)) {
      optionalVars :+= CMakeVar(
        "SOCT_NEEDS_FATFS", "ON",
        "This system needs the FatFS library for filesystem support (e.g. SD card)",
        compileDef = true,
      )
    }
    if (config.params(CLINTKey).isDefined) {
      optionalVars :+= CMakeVar(
        "SOCT_CLINT_BASE",
        config.params(CLINTKey).get.baseAddress.toString,
        "Base address of the CLINT (Core Local Interruptor)",
        compileDef = true,
      )
    }
    val optional = if (optionalVars.isEmpty) "" else "\n" + CMakeVar.render(optionalVars)

    // Additional information for targets
    val targetSpecific = config.args.target match {
      case Targets.Verilator =>
        val vars = Seq(
          CMakeVar("SOCT_VERILATOR_TOP_MODULE", config.topModule.fold(_.getSimpleName, _.getSimpleName),
            "The top-level module name for the Verilog design", compileDef = true, quoted = true),
          CMakeVar("SOCT_SIM_EXE", SOCT_SIMULATOR_EXE,
            "The name of the executable to build for simulating this system"),
        )
        s"""########################################################
           |# Additional information for Verilator simulation target
           |########################################################
           |
           |${CMakeVar.render(vars)}
           |""".stripMargin

      case Targets.Vivado =>
        val vars = Seq(
          CMakeVar("SOCT_VIVADO_BOARD", config.args.board.map(_.toString).getOrElse("unknown"),
            "The name of the FPGA board this system is designed for", compileDef = true, quoted = true),
        )
        s"""####################################################
           |# Additional information for Vivado synthesis target
           |####################################################
           |
           |${CMakeVar.render(vars)}
           |""".stripMargin

      case Targets.Yosys =>
        s"""###################################################
           |# Additional information for Yosys synthesis target
           |###################################################
           |
           |# (none for now)
           |""".stripMargin
    }

    s"""$common
       |$optional
       |$targetSpecific
       |""".stripMargin
  }
}