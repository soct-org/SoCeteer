package soct.system.vivado

import org.chipsalliance.cde.config.Parameters
import soct.SOCTLauncher.SOCTConfig
import soct.system.soceteer.LastRocketSystem
import soct.{BdBuilderKey, SOCTArgs, SOCTRemote, VivadoSOCTPaths, log}

import java.nio.file.{Files, Path}
import scala.reflect.io.Path.jfile2path
import scala.util.matching.Regex


object SOCTVivado {

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
    val vFiles = Files.walk(boardPaths.verilogSrcDir)
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
      throw VivadoDesignException(s"Could not find top module file for module ${config.topModuleName} in ${boardPaths.verilogSrcDir}")
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
      throw VivadoDesignException(
        s"Could not find module declaration for top module $topModuleName"
      )
    }
    m.group(2).linesIterator.toSeq
  }


  private def patchPortLines(topVerilog: String, topModuleName: String, newPortLines: Seq[String]): String = {
    val regex = verilogModuleRegex(topModuleName)

    val m = regex.findFirstMatchIn(topVerilog).getOrElse {
      throw VivadoDesignException(
        s"Could not find module declaration for top module $topModuleName"
      )
    }
    val moduleStart = m.group(1)
    val moduleEnd = m.group(3)
    val ports = newPortLines.mkString("\n") + "\n"
    val replacement = s"$moduleStart$ports$moduleEnd"
    regex.replaceFirstIn(topVerilog, replacement)
  }


  /**
   * Prepare the elaborated design for Vivado: finalize the block design, patch the top-level
   * Verilog with Vivado port annotations, and write all generated TCL/XDC collateral to the
   * board paths.
   *
   * @param boardPaths         output paths of the Vivado flow
   * @param config             SOCT configuration of the elaborated design
   * @param removeVerification whether to delete the generated `verification` directory (its
   *                           sources are not synthesizable)
   * @return true if the TCL scripts were generated, false if no BdBuilder was configured
   * @throws VivadoDesignException if no elaborated RocketSystem instance exists, the top module
   *                               file cannot be found, or its module declaration cannot be parsed
   */
  def prepareForVivado(boardPaths: VivadoSOCTPaths, config: SOCTConfig, removeVerification: Boolean = true): Boolean = {
    val rs = LastRocketSystem.instance.getOrElse {
      throw VivadoDesignException("No RocketSystem instance found for Vivado generation - did you elaborate the design?")
    }
    implicit val p: Parameters = rs.p
    val bdOpt = p(BdBuilderKey)
    if (bdOpt.isEmpty) {
      soct.log.warn("BDBuilder not found in parameters, not generating TCL scripts")
      return false
    }
    implicit val bd: SOCTBdBuilder = bdOpt.get

    bd.finalizeDesign()

    val topModuleFile: Path = getTopModuleFile(boardPaths, config)
    val topVerilog = Files.readString(topModuleFile)

    val portLines = extractPortLines(topVerilog, config.topModuleName)
    val transformed = patchPortLines(topVerilog, config.topModuleName, bd.addPortMappings(portLines))
    Files.writeString(topModuleFile, transformed)

    val initTCL = bd.generateInitScript()
    Files.writeString(boardPaths.tclInitFile, initTCL)

    val tcTCL = bd.generateTimingConstraintsTcl()
    Files.writeString(boardPaths.defaultTCGenerator, tcTCL)

    val bdTCL = bd.generateBoardTcl()
    Files.writeString(boardPaths.defaultBdGenerator, bdTCL)

    val synthTCL = bd.generateSynthesisTcl()
    Files.writeString(boardPaths.defaultSynthGenerator, synthTCL)

    val xdcByPath = bd.generateConstraintsTcls(boardPaths.xdcDir)
    xdcByPath.foreach { case (path, xdc) =>
      Files.writeString(path, xdc)
    }

    // dump collaterals for all components
    bd.emitCollaterals(boardPaths.verilogSrcDir)

    if (removeVerification) {
      val verificationDir = boardPaths.verilogSrcDir.resolve("verification")
      if (verificationDir.toFile.exists()) {
        soct.log.info(s"Removing verification directory at $verificationDir for Vivado synthesis")
        verificationDir.toFile.deleteRecursively()
      }
    }
    true
  }

  /**
   * Run Vivado in batch mode on the generated init script to create the project. When remote
   * Vivado is configured (`--remote-dir` + `--ssh-config`), the workspace is pushed first, the
   * command runs over SSH, and the results are pulled back.
   *
   * @param args       launcher arguments (Vivado binary, remote settings)
   * @param boardPaths output paths of the Vivado flow
   * @param config     SOCT configuration of the elaborated design
   * @throws VivadoDesignException if the init script's path cannot be mapped to the remote workspace
   * @throws RuntimeException if the rsync push/pull of the workspace fails
   */
  def generateProject(args: SOCTArgs, boardPaths: VivadoSOCTPaths, config: SOCTConfig): Unit = {
    var cmd = Seq(args.vivado.get.toAbsolutePath.toString, "-mode", "batch", "-source")
    var file = boardPaths.tclInitFile.toAbsolutePath
    if (args.useRemoteVivado) {
      if (args.remoteDir.isEmpty) {
        soct.log.warn("Remote Vivado requested but no remote directory provided, ignoring remote Vivado option")
      }
      if (args.openSSHConfig.isEmpty) {
        soct.log.warn("Remote Vivado requested but no OpenSSH config provided, ignoring remote Vivado option")
      }

      if (args.remoteDir.isDefined && args.openSSHConfig.isDefined) {
        // First, sync the design files to the remote directory using rsync over SSH
        val remoteWorkspace = SOCTRemote.pushDir(args.workspaceDir, args).get
        cmd = Seq("ssh", args.openSSHConfig.get) ++ cmd
        // Relativize the tcl file path to the remote directory
        file = SOCTRemote.toRemote(Map(args.workspaceDir -> remoteWorkspace), file).getOrElse {
          throw VivadoDesignException(s"Could not find remote path for ${file.toAbsolutePath} in path map")
        }
      }
    }

    cmd = cmd :+ file.toString

    soct.log.info(s"Running Vivado with command: ${cmd.mkString(" ")}. This may take a while...")

    val process = new ProcessBuilder(cmd: _*)
      .redirectOutput(if (log.underlying.isDebugEnabled) ProcessBuilder.Redirect.INHERIT else ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .start()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
      soct.log.error(s"Vivado process exited with code $exitCode")
    }
    SOCTRemote.pullDir(args.workspaceDir, args)
  }
}
