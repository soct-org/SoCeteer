package soct.xilinx


import soct.SOCTLauncher.SOCTConfig
import soct.{BoardSOCTPaths, SOCTUtils}
import soct.xilinx.fpga.{FPGA, ZCU104}

import java.nio.file.{Files, Path}


/**
 * Exception thrown during evaluation of a Xilinx design
 */
case class XilinxDesignException(private val message: String = "",
                                 private val cause: Throwable = None.orNull)
  extends Exception(message, cause)


/**
 * Registry for known FPGA boards.
 */
object FPGARegistry {

  /**
   * Resolve a board by name
   *
   * @param name Name of the board
   * @return Some(FPGA) if found, None otherwise
   */
  def resolve(name: String): Option[FPGA] = {
    val comp = name.toLowerCase

    if (comp == ZCU104.friendlyName.toLowerCase) {
      Some(ZCU104)
    } else {
      None
    }
  }

  /**
   * List of available boards
   */
  def availableBoards: Seq[String] = {
    Seq(
      ZCU104.friendlyName
    )
  }
}


object SOCTVivado {


  val DEFAULT_MEMORY_ADDR_64: BigInt = BigInt("80000000", 16)

  val DEFAULT_MEMORY_ADDR_32: BigInt = BigInt("40000000", 16)

  val DEFAULT_MMIO_ADDR = "0x60000000"

  def generate(boardPaths: BoardSOCTPaths, config: SOCTConfig): Unit = {
    // Vivado does not allow a SystemVerilog top-level.
    // We do a highly illegal trick here by just renaming the file extension,
    // hoping that Chisel did not include any SystemVerilog-specific constructs in the top-level module.
    // Note that this is not guaranteed to work and may break in future Chisel versions.
    if (boardPaths.verilogSystem.toFile.isDirectory) {
      // Get all files in the directory recursively
      val svFiles = Files.walk(boardPaths.verilogSystem)
        .filter(p => p.toString.endsWith(".sv"))
        .toArray
        .map(_.asInstanceOf[Path])

      val topModuleName = config.topModule.fold(_.getSimpleName, _.getSimpleName)
      // We now check if the name of the top module matches any of the files
      val topModuleFileOpt = svFiles.find { p =>
        p.getFileName.toString.equals(s"$topModuleName.sv")
      }
      if (topModuleFileOpt.isEmpty) {
        throw XilinxDesignException(s"Could not find SystemVerilog file for top module $topModuleName in directory ${boardPaths.verilogSystem}")
      } else {
        val topModuleFile = topModuleFileOpt.get
        val newTopModuleFile = topModuleFile.resolveSibling(topModuleFile.getFileName.toString.replace(".sv", ".v"))
        Files.move(topModuleFile, newTopModuleFile)
        soct.log.info(s"Renamed top module file ${topModuleFile.getFileName} to ${newTopModuleFile.getFileName} for Vivado compatibility")
      }
    }
  }

  def generateProject(tclFile: Path, vivado: Path, vivadoSettings: Option[Path]): Unit = {
    // On windows, it should be a .bat file
    if (SOCTUtils.isWindows) {
      println("Not implemented yet")
    } else if (SOCTUtils.isUnix) {
      var cmd = s"${vivado.toAbsolutePath} -mode batch -source ${tclFile.toAbsolutePath}"
      if (vivadoSettings.isDefined) {
        cmd = s"source ${vivadoSettings.get} && $cmd"
      }
      val args = Seq("bash", "-c", cmd)
      println("Running Vivado with command: " + args.mkString(" "))
      val process = new ProcessBuilder(args: _*).directory(tclFile.getParent.toFile).inheritIO().start()
      val exitCode = process.waitFor()
      if (exitCode != 0) {
        throw new RuntimeException(s"Vivado failed with exit code $exitCode")
      }
    }
  }
}
