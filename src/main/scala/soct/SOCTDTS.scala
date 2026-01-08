package soct

import java.nio.file.{Files, Path}
import scala.util.matching.Regex
import scala.util.chaining.scalaUtilChainingOps

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

object DTSCMakeGenerator {

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

    s"""# Auto-generated CMake file for SOCT
       |cmake_minimum_required(VERSION 3.20)
       |
       |set(SOCETEER_ROOT "${SOCTPaths.projectRoot.toAbsolutePath.toString}")
       |set(SOCT_ARCH "$march")
       |set(SOCT_ABI "${config.mabi}")
       |set(SOCT_XLEN "${config.args.xlen}")
       |set(SOCT_NCPUS "${DTSExtractor.countCPUs(dtsContent)}")
       |set(SOCT_SYSTEM_ROOT "${paths.systemDir.toAbsolutePath.toString}")
       |set(SOCT_DTS "${paths.dtsFile.toAbsolutePath.toString}")
       |set(SOCT_DTB "${paths.dtbFile.toAbsolutePath.toString}")
       |set(SOCT_BOOTROM_IMG "${paths.bootromImgFile.toAbsolutePath.toString}") # Is used in bootrom mode to determine where to output the image
       |""".stripMargin
  }
}