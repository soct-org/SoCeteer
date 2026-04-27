package soct

import soct.SOCTNames.SOCT_SIMULATOR_EXE

import java.nio.file.{Files, Path}

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

    val common =
      s"""# Auto-generated CMake file for SOCT
         |cmake_minimum_required(VERSION 3.20)
         |
         |# The actual path to this CMake file - works even through symlinks
         |file(REAL_PATH "$${CMAKE_CURRENT_LIST_FILE}" _real_file)
         |get_filename_component($thisDir "$${_real_file}" DIRECTORY)
         |
         |# The root directory of the SoCeteer project - all static files are located under this directory
         |cmake_path(SET SOCETEER_ROOT NORMALIZE "${relFromRealBase(SOCTPaths.projectRoot)}")
         |
         |# The version of soceteer used to generate this system
         |set(SOCETEER_VERSION "$version")
         |
         |# The name of the system configuration
         |set(SOCT_CONFIG_NAME "${config.configName}")
         |
         |# Whether this system was build for an FPGA board, Verilator simulation etc.
         |set(SOCT_TARGET "${config.args.target.name}")
         |
         |# The RISC-V architecture string extracted from the DTS
         |set(SOCT_ARCH "$march")
         |
         |# The RISC-V ABI to use for compiling binaries for this system
         |set(SOCT_ABI "${config.mabi}")
         |
         |# The XLEN of the system
         |set(SOCT_XLEN "${config.args.xlen}")
         |
         |# The number of CPU cores in the system, extracted from the DTS
         |set(SOCT_NCPUS "${DTSExtractor.countCPUs(dtsContent)}")
         |
         |# The Verilog source files for this system
         |set(SOCT_VSRCS "${rel(paths.verilogSrcDir)}")
         |
         |# The device tree file for this system
         |set(SOCT_DTS "${rel(paths.dtsFile)}")
         |
         |# The compiled device tree blob for this system
         |set(SOCT_DTB "${rel(paths.dtbFile)}")
         |
         |# The bootrom image for this system
         |set(SOCT_BOOTROM_IMG "${rel(paths.bootromImgFile)}")
         |
         |# The directory where compiled ELF files for this system are stored
         |set(SOCT_ELFS_DIR "${rel(paths.elfsDir)}")
         |
         |# Whether this system needs the FatFS library for filesystem support
         |# For example, an FPGA design that includes an SD card interface would need FatFS, while a simple Verilator simulation without storage peripherals might not.
         |# It is used for the soctglue library to conditionally include FatFS source files and headers (thus increasing compilation time and binary size only when needed).
         |set(SOCT_NEEDS_FATFS ${if (config.params(NeedsFatFS)) "ON" else "OFF"})
         |
         |# A build directory that can be used for temporary files during the build process (e.g., when building the bootrom with CMake).
         |set(SOCT_BUILD_DIR "${rel(paths.buildDir)}")
         |""".stripMargin

    // Additional information for targets
    val targetSpecific = config.args.target match {
      case Targets.Verilator =>
        s"""########################################################
           |# Additional information for Verilator simulation target
           |########################################################
           |
           |# The top-level module name for the Verilog design - can be passed to Verilator to specify the top module to simulate
           |set(SOCT_VERILATOR_TOP_MODULE "${config.topModule.fold(_.getSimpleName, _.getSimpleName)}")
           |
           |# The name of the executable to build for simulating this system - can be used as the target name in a CMake build command
           |set(SOCT_SIM_EXE "$SOCT_SIMULATOR_EXE")
           |""".stripMargin
      case Targets.Vivado =>
        s"""####################################################
           |# Additional information for Vivado synthesis target
           |####################################################
           |
           |# The name of the FPGA board this system is designed for
           |set(SOCT_VIVADO_BOARD "${config.args.board.getOrElse("unknown")}")
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
       |
       |$targetSpecific
       |""".stripMargin
  }
}