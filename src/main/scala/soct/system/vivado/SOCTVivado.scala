package soct.system.vivado

import chisel3.Data
import chisel3.reflect.DataMirror
import org.chipsalliance.cde.config.Parameters
import soct.SOCTLauncher.SOCTConfig
import soct.system.soceteer.LastRocketSystem
import soct.system.vivado.fpga.{FPGA, ZCU104}
import soct.{HasBdBuilder, VivadoSOCTPaths}

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.reflect.io.Path.jfile2path
import scala.util.matching.Regex


/**
 * Exception thrown during evaluation of a Xilinx design
 */
case class XilinxDesignException(private val message: String = "",
                                 private val cause: Throwable = None.orNull) extends Exception(message, cause)



object SOCTVivado {


  val DEFAULT_MEMORY_ADDR_64: BigInt = BigInt("80000000", 16)

  val DEFAULT_MEMORY_ADDR_32: BigInt = BigInt("40000000", 16)

  val DEFAULT_MMIO_ADDR = "0x60000000"

  val TAB_SIZE = 2

  /** Convert a name to snake_case */
  def snake(name: String): String = {
    name.toLowerCase.replace(".", "_")
  }

  /** Convert a Chisel Data port to a Xilinx port reference string */
  def toXilinxPortRef[T <: Data](x: T)(implicit bd: SOCTBdBuilder): String = {
    require(DataMirror.isIO(x), s"Port ${x.instanceName} is not an IO port")
    val topInst = bd.topInstance().instanceName
    s"$topInst/${snake(x.instanceName)}"
  }

  /**
   * Add Vivado port mappings to the given lines
   *
   * @param portLines    Lines of the Verilog file containing the port declarations
   * @param portMappings Map of port names to Vivado attribute strings
   * @return Modified lines with Vivado annotations added
   */
  private def addPortMappings(
                               portLines: Seq[String],
                               portMappings: Map[String, Seq[String]],
                             ): Seq[String] = {
    val lines = mutable.Buffer.from(portLines)
    portMappings.foreach { case (portName, attrStrings) =>
      val lineIdxOpt = lines.zipWithIndex.find { case (line, _) =>
        line.contains(s"$portName")
      }.map { case (_, idx) => idx }
      if (lineIdxOpt.isEmpty) {
        soct.log.warn(s"Could not find port line for port $portName to add Vivado annotation")
      } else {
        val lineIdx = lineIdxOpt.get
        val ws = "\t" * TAB_SIZE
        // Insert the annotations before the line - see https://docs.amd.com/r/en-US/ug994-vivado-ip-subsystems/General-Usage
        attrStrings.reverse.foreach { attrString =>
          lines.insert(lineIdx, ws + attrString)
        }
      }
    }
    lines.toSeq
  }

  /**
   * Vivado does not allow a SystemVerilog top-level.
   * We do a highly illegal trick here by just renaming the file extension,
   * hoping that Chisel did not include any SystemVerilog-specific constructs in the top-level module.
   * Note that this is not guaranteed to work and may break in future Chisel versions.
   *
   * @param boardPaths Paths to the board
   * @param config     SOCT configuration
   * @param convert    Whether to perform the conversion (renaming). If false, just returns the path.
   * @return Path to the (new) top-level Verilog file
   */
  private def getTopModuleFile(boardPaths: VivadoSOCTPaths, config: SOCTConfig, convert: Boolean = true): Path = {
    val endings = Seq(".v", ".sv")

    // Get all files in the directory recursively
    val vFiles = Files.walk(boardPaths.verilogSrc)
      .filter(p => Files.isRegularFile(p) && endings.exists(e => p.getFileName.toString.endsWith(e)))
      .toArray
      .map(_.asInstanceOf[Path])

    // We now check if the name of the top module matches any of the files
    val topModuleFileOpt = vFiles.find { p =>
      val fileName = p.getFileName.toString
      val baseName = fileName.substring(0, fileName.indexOf('.')) // strip all extensions
      baseName == config.topModuleName
    }

    if (topModuleFileOpt.isEmpty) {
      throw XilinxDesignException(s"Could not find top module file for module ${config.topModuleName} in ${boardPaths.verilogSrc}")
    }

    val topModuleFile = topModuleFileOpt.get
    val fileName = topModuleFile.getFileName.toString

    // If no conversion is needed or is verilog already, return as is
    if (!convert || fileName.endsWith(".v"))
      return topModuleFileOpt.get

    val newTopModuleFile = topModuleFile.resolveSibling(fileName.replace(".sv", ".v"))
    Files.move(topModuleFile, newTopModuleFile)
    soct.log.info(s"Renamed top module file ${topModuleFile.getFileName} to ${newTopModuleFile.getFileName} for Vivado compatibility")
    newTopModuleFile
  }

  /**
   * Regex to match a Verilog module declaration. Has three capture groups:
   * 1: module moduleName (
   * 2: port declarations
   * 3: );
   *
   * @param moduleName Name of the module
   * @return Regex to match the module declaration
   */
  private def verilogModuleRegex(moduleName: String): Regex =
    s"""(?s)(module\\s+$moduleName\\s*\\()(.*?)(\\)\\s*;)""".r


  private def extractPortLines(topVerilog: String, topModuleName: String): Seq[String] = {
    val m = verilogModuleRegex(topModuleName).findFirstMatchIn(topVerilog).getOrElse {
      throw XilinxDesignException(
        s"Could not find module declaration for top module $topModuleName"
      )
    }
    m.group(2).linesIterator.toSeq
  }


  private def patchPortLines(topVerilog: String, topModuleName: String, newPortLines: Seq[String]): String = {
    val regex = verilogModuleRegex(topModuleName)

    val m = regex.findFirstMatchIn(topVerilog).getOrElse {
      throw XilinxDesignException(
        s"Could not find module declaration for top module $topModuleName"
      )
    }
    val moduleStart = m.group(1)
    val moduleEnd = m.group(3)
    val ports = newPortLines.mkString("\n") + "\n"
    val replacement = s"$moduleStart$ports$moduleEnd"
    regex.replaceFirstIn(topVerilog, replacement)
  }


  def prepareForVivado(boardPaths: VivadoSOCTPaths, config: SOCTConfig, removeVerification: Boolean = true): Boolean = {
    val rs = LastRocketSystem.instance.getOrElse {
      throw XilinxDesignException("No RocketSystem instance found for Vivado generation - did you elaborate the design?")
    }
    implicit val p: Parameters = rs.p
    val bdOpt = p(HasBdBuilder)
    if (bdOpt.isEmpty) {
      soct.log.warn("BDBuilder not found in parameters, not generating TCL scripts")
      return false
    }
    implicit val bd: SOCTBdBuilder = bdOpt.get


    val topModuleFile: Path = getTopModuleFile(boardPaths, config)
    val topVerilog = Files.readString(topModuleFile)

    val portLines = extractPortLines(topVerilog, config.topModuleName)
    val portMappings = bd.portModifications()
    val transformed = patchPortLines(topVerilog, config.topModuleName, addPortMappings(portLines, portMappings))
    Files.writeString(topModuleFile, transformed)

    val initTCL = bd.generateInitScript()
    Files.writeString(boardPaths.tclInitFile, initTCL)

    val bdTCL = bd.generateBoardTcl()
    Files.writeString(boardPaths.bdLoadFile, bdTCL)

    // dump collaterals for all components
    bd.emitCollaterals(boardPaths.verilogSrc)

    if (removeVerification) {
      val verificationDir = boardPaths.verilogSrc.resolve("verification")
      if (verificationDir.toFile.exists()) {
        soct.log.info(s"Removing verification directory at $verificationDir for Vivado synthesis")
        verificationDir.toFile.deleteRecursively()
      }
    }
    true
  }

  def generateProject(tclFile: Path, vivado: Path, workdir: Path): Unit = {
    val file = tclFile.toAbsolutePath.toString
    var cmd = Seq(vivado.toAbsolutePath.toString, "-mode", "batch", "-source")
    cmd :+= file
    val process = new ProcessBuilder(cmd: _*)
      .directory(workdir.toFile)
      .inheritIO()
      .start()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
      throw new RuntimeException(s"Vivado failed with exit code $exitCode")
    }
  }
}
