package soct.system.vivado

import org.chipsalliance.cde.config.Parameters
import soct.SOCTLauncher.SOCTConfig
import soct.system.soceteer.LastRocketSystem
import soct.{BdBuilderKey, BuildStage, SOCTArgs, SOCTRemote, SOCTUtils, VivadoSOCTPaths, VivadoTarget, log}

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

    // Both stage run scripts are always emitted so they can be sourced by hand in the GUI too;
    // the build.tcl entry point (init + the chosen run script) is only written when a build stage
    // is requested via the target (vivado.syn / vivado.bs).
    Files.writeString(boardPaths.defaultSynthGenerator, bd.generateRunTcl(BuildStage.Synthesis))
    Files.writeString(boardPaths.defaultImplGenerator, bd.generateRunTcl(BuildStage.Bitstream))
    config.args.target match {
      case v: VivadoTarget => v.buildStage.foreach(stage =>
        Files.writeString(boardPaths.tclBuildFile, buildEntryTcl(boardPaths, stage)))
      case _ =>
    }

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

  /**
   * The `build.tcl` entry point for a detached build: source the project init script (creates the
   * project + block design), then the stage's run script. Both are siblings in the same directory,
   * located relative to this script so the workspace stays relocatable.
   *
   * @param boardPaths output paths of the Vivado flow
   * @param stage      the build stage whose run script to source
   * @return the TCL script as a string
   */
  private def buildEntryTcl(boardPaths: VivadoSOCTPaths, stage: BuildStage): String = {
    val initName = boardPaths.tclInitFile.getFileName.toString
    val runName = (stage match {
      case BuildStage.Synthesis => boardPaths.defaultSynthGenerator
      case BuildStage.Bitstream => boardPaths.defaultImplGenerator
    }).getFileName.toString
    s"""# Auto-generated by SOCT - detached ${stage.name} build entry point.
       |# Sources the project init script (creates the project + block design), then the run script.
       |set build_dir [file dirname [file normalize [info script]]]
       |source $$build_dir/$initName
       |source $$build_dir/$runName
       |""".stripMargin
  }

  /** Single-quote a string for a POSIX remote shell, escaping embedded single quotes. */
  private def shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

  /**
   * Run a command over SSH and capture its trimmed stdout. Stderr is inherited so the user sees
   * any SSH errors. Only for short outputs (a PID, a `uname` line) - it reads stdout fully.
   *
   * @return (exit code, trimmed stdout)
   */
  private def sshCapture(sshHost: String, remoteCmd: String): (Int, String) = {
    val proc = new ProcessBuilder("ssh", sshHost, remoteCmd)
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .start()
    val out = scala.io.Source.fromInputStream(proc.getInputStream, "UTF-8").mkString.trim
    (proc.waitFor(), out)
  }

  /**
   * Probe the remote host's OS via `uname -s`. Returns the OS name (e.g. `Linux`, `Darwin`) when
   * the remote provides a POSIX shell, or None when `uname` is unavailable - which is the case for
   * a native Windows (cmd/PowerShell) remote, where the detached-build command cannot run.
   */
  private def remoteUname(sshHost: String): Option[String] = {
    val (exit, out) = sshCapture(sshHost, "uname -s")
    if (exit == 0 && out.nonEmpty) Some(out) else None
  }

  /**
   * Launch an automatic Vivado build (synthesis or bitstream) as a detached process and return
   * immediately - the build outlives the launcher. Locally, Vivado runs with its output redirected
   * to a log file (redirecting to a file, not a pipe, is what lets it survive the JVM exiting on
   * both POSIX and Windows). With `--use-remote-vivado`, the workspace is pushed and Vivado is
   * started under `nohup` on the remote so the local `ssh` returns at once - results stay on the
   * remote until pulled back with `--sfr`. The remote must provide a POSIX shell (the launch uses
   * `nohup sh -c ... &`); a native Windows remote is refused up front (see [[remoteUname]]). Either
   * way the log location, a follow command, and how to kill the build (with its PID) are printed.
   *
   * @param args       launcher arguments (Vivado binary, remote settings, parallelism)
   * @param boardPaths output paths of the Vivado flow
   * @param config     SOCT configuration of the elaborated design
   * @param stage      the build stage to run
   */
  def launchBuild(args: SOCTArgs, boardPaths: VivadoSOCTPaths, config: SOCTConfig, stage: BuildStage): Unit = {
    val vivado = args.vivado.get.toAbsolutePath.toString
    if (args.useRemoteVivado) launchRemoteBuild(args, boardPaths, stage, vivado)
    else launchLocalBuild(args, boardPaths, stage, vivado)
  }

  private def launchLocalBuild(args: SOCTArgs, boardPaths: VivadoSOCTPaths, stage: BuildStage, vivado: String): Unit = {
    val logFile = boardPaths.vivadoBuildLog
    val buildTcl = boardPaths.tclBuildFile.toAbsolutePath.toString
    val cmd = Seq(vivado, "-mode", "batch", "-source", buildTcl)

    // Detach: redirect combined output to the log file and DO NOT waitFor - a started process is
    // not killed when the JVM exits, and the file (not a pipe) keeps it independent of us.
    val process = new ProcessBuilder(cmd: _*)
      .directory(boardPaths.systemDir.toFile)
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile))
      .start()

    val follow =
      if (SOCTUtils.isWindows) s"Get-Content -Wait -Tail 40 ${logFile.toAbsolutePath}"
      else s"tail -f ${logFile.toAbsolutePath}"
    val kill =
      if (SOCTUtils.isWindows) s"taskkill /PID ${process.pid()} /F"
      else s"kill ${process.pid()}"
    soct.log.info(
      s"""Launched detached Vivado ${stage.name} build (PID ${process.pid()}). It runs independently of this process.
         |  Command: ${cmd.mkString(" ")}
         |  Log:     ${logFile.toAbsolutePath}
         |  Follow:  $follow
         |  Kill:    $kill""".stripMargin)
  }

  private def launchRemoteBuild(args: SOCTArgs, boardPaths: VivadoSOCTPaths, stage: BuildStage, vivado: String): Unit = {
    if (args.remoteDir.isEmpty || args.openSSHConfig.isEmpty) {
      soct.log.warn("Remote build requested (--use-remote-vivado) but --remote-dir and/or --ssh-config are missing; not launching.")
      return
    }
    val sshHost = args.openSSHConfig.get

    // Fail fast before rsyncing the workspace: the detached launch runs 'nohup sh -c ... &', which
    // needs a POSIX shell on the remote. A native Windows (cmd/PowerShell) remote has no 'uname'
    // and cannot run it - refuse with a clear message rather than producing a broken launch.
    val remoteOs = remoteUname(sshHost).getOrElse {
      throw VivadoDesignException(
        s"The remote host '$sshHost' does not provide a POSIX shell ('uname' failed). Detached remote " +
          "builds run 'nohup sh -c ...', which requires a POSIX shell - Linux or macOS, or WSL/Git-Bash " +
          "on Windows. A native Windows (cmd/PowerShell) remote is not supported.")
    }

    val remoteWorkspace = SOCTRemote.pushDir(args.workspaceDir, args).getOrElse {
      throw VivadoDesignException("Failed to push the workspace to the remote host for the build.")
    }
    val pathMap = Map(args.workspaceDir -> remoteWorkspace)
    def toRemote(p: Path): String = SOCTRemote.toRemote(pathMap, p.toAbsolutePath).getOrElse {
      throw VivadoDesignException(s"Could not map ${p.toAbsolutePath} into the remote workspace $remoteWorkspace.")
    }.toString
    val remoteBuildTcl = toRemote(boardPaths.tclBuildFile)
    val remoteLog = toRemote(boardPaths.vivadoBuildLog)

    // Run the whole launch through an explicit POSIX shell so it works regardless of the remote's
    // LOGIN shell (bash/zsh/fish/csh/...): sh handles the backgrounding and $!, which fish and csh
    // spell differently. nohup + backgrounding + redirecting all std streams off the ssh channel
    // lets ssh return at once while the build keeps running after we disconnect; 'echo $!' hands us
    // the remote PID so we can print how to kill it. Paths are double-quoted for spaces.
    val script = s"""nohup "$vivado" -mode batch -source "$remoteBuildTcl" > "$remoteLog" 2>&1 < /dev/null & echo $$!"""
    val remoteCmd = s"sh -c ${shQuote(script)}"

    val (exit, pid) = sshCapture(sshHost, remoteCmd)
    if (exit != 0) {
      soct.log.error(s"Failed to launch the remote build (ssh exit code $exit).")
      return
    }
    val killHint =
      if (pid.matches("\\d+")) s"ssh $sshHost kill $pid"
      else s"(PID unknown; find it on '$sshHost', e.g. pgrep -f 'vivado.*$remoteBuildTcl')"
    soct.log.info(
      s"""Launched detached Vivado ${stage.name} build on '$sshHost' ($remoteOs, PID $pid). It keeps running after this process exits.
         |  Remote log: $remoteLog
         |  Follow:     ssh $sshHost tail -f $remoteLog
         |  Kill:       $killHint
         |  When the log shows the build finished, pull the results back with --sfr (sync-from-remote).""".stripMargin)
  }
}
