package soct

import firtoolresolver.FirtoolBinary
import org.chipsalliance.cde.config.{Config, Parameters}
import org.json4s.{CustomSerializer, JNull, JString}

import java.nio.file.{Path, Paths}
import scala.util.Try


// JSON4S serializer for java.nio.file.Path
object PathSerializer extends CustomSerializer[Path](_ => ( {
  case JString(s) => Paths.get(s)
}, {
  case p: Path => JString(p.toString)
}
))

object TargetsSerializer extends CustomSerializer[Targets](_ => ( {
  case JString(s) => Targets.parse(s)
  case JNull => Targets.Verilator
}, {
  case t: Targets => JString(t.name)
}
))

// Internal Bug exception with stacktrace and message
class InternalBugException(message: String) extends Exception(message) {
  override def toString: String = s"InternalBugException: $message\n${getStackTrace.mkString("\n")}"
}


/**
 * Handles remote sync of output directories to a remote host via rsync over SSH.
 * Requires both --ssh-config (an OpenSSH host alias) and --remote-dir to be set.
 */
object SOCTRemote {

  /**
   * Given a map of local output directories to their corresponding remote directories, and a local path, returns the corresponding remote path if the local path is within one of the mapped local directories. This is used to translate local paths to their remote equivalents after syncing.
   *
   * @param map       A map of local output directories to their corresponding remote directories (e.g. Map("/local/workspace" -> "/remote/outputs/workspace")).
   * @param localPath A local path that may be within one of the mapped local directories.
   * @return The corresponding remote path if localPath is within one of the mapped local directories, or None if it is not. For example, if map contains an entry "/local/workspace" -> "/remote/outputs/workspace", and localPath is "/local/workspace/build", this would return Some("/remote/outputs/workspace/build"). If localPath is "/local/otherdir/file.txt", this would return None since it is not within any of the mapped local directories.
   */
  def toRemote(map: Map[Path, Path], localPath: Path): Option[Path] = {
    // Find the first entry in the map where the localPath starts with the key (local output directory)
    map.find { case (localDir, _) => localPath.startsWith(localDir) }
      .map { case (localDir, remoteDir) =>
        // Replace the localDir prefix in localPath with the corresponding remoteDir
        remoteDir.resolve(localDir.relativize(localPath))
      }
  }

  private def rsync(cmd: Seq[String], what: String): Unit = {
    log.debug(s"Running: ${cmd.mkString(" ")}")
    val exitCode = new ProcessBuilder(cmd: _*).inheritIO().start().waitFor()
    if (exitCode != 0) throw new RuntimeException(s"rsync failed ($what), exit code $exitCode")
  }

  private def validate(args: SOCTArgs): Boolean = {
    if (args.openSSHConfig.isEmpty || args.remoteDir.isEmpty) {
      log.warn(s"SSH host and remote root must be set to rsync paths")
      return false
    }
    true
  }

  /**
   * Push a local directory to the remote host using rsync over SSH. The local directory will be copied to the remote root directory specified in args, preserving the directory name. For example, if localDir is "/local/workspace/build" and args.remoteDir is "/remote/outputs", this will copy the contents of "/local/workspace/build" to "/remote/outputs/build" on the remote host specified by args.openSSHConfig. Returns the remote path of the pushed directory if successful, or None if validation fails (e.g. missing SSH config or remote dir).
   *
   * @param localDir The local directory to push to the remote host. This directory must exist and will be copied to the remote host using rsync.
   * @param args     The SOCTArgs containing the SSH configuration and remote directory information needed to perform the rsync. Specifically, args.openSSHConfig should contain the OpenSSH host alias (e.g. "user@remotehost") and args.remoteDir should contain the remote root directory (e.g. "/remote/outputs") where the local directory will be copied to.
   * @return The remote path of the pushed directory if the push is successful, or None if validation fails (e.g. missing SSH config or remote dir). For example, if localDir is "/local/workspace/build", args.openSSHConfig is "user@remotehost", and args.remoteDir is "/remote/outputs", this would return Some("/remote/outputs/build") if the rsync is successful.
   * @throws RuntimeException if the rsync command fails (non-zero exit code)
   */
  def pushDir(localDir: Path, args: SOCTArgs): Option[Path] = {
    if (!validate(args)) return None
    val sshHost = args.openSSHConfig.get
    val remoteRoot = args.remoteDir.get
    val dirName = localDir.getFileName.toString
    // Create remote root directory
    val cmd = Seq("ssh", sshHost, "mkdir", "-p", s"$remoteRoot/")
    log.info(s"Ensuring remote root directory exists with command: ${cmd.mkString(" ")}")
    val exitCode = new ProcessBuilder(cmd: _*).start().waitFor()

    if (exitCode != 0) throw new RuntimeException(s"Failed to create remote root directory $remoteRoot, exit code $exitCode")
    val localSrc = s"${localDir.toAbsolutePath}/"
    val remoteDst = s"$sshHost:$remoteRoot/$dirName/"
    rsync(Seq("rsync", "-az", "--progress", "--stats", localSrc, remoteDst), s"push $localSrc -> $remoteDst")

    Some(remoteRoot.resolve(dirName))
  }

  /**
   * Pull a remote directory from the remote host to the local machine using rsync over SSH. The remote directory is specified by combining the remote root directory from args with the name of the local directory. For example, if localDir is "/local/workspace/build" and args.remoteDir is "/remote/outputs", this will pull the contents of "/remote/outputs/build" from the remote host specified by args.openSSHConfig to "/local/workspace/build" on the local machine. The local directory will be created if it does not exist. Returns Unit if successful, or does nothing if validation fails (e.g. missing SSH config or remote dir).
   *
   * @param localDir The local directory to pull the remote contents into. This directory will be created if it does not exist. The name of this local directory is used to determine the corresponding remote directory name under the remote root specified in args.remoteDir.
   * @param args     The SOCTArgs containing the SSH configuration and remote directory information needed to perform the rsync. Specifically, args.openSSHConfig should contain the OpenSSH host alias (e.g. "user@remotehost") and args.remoteDir should contain the remote root directory (e.g. "/remote/outputs") where the remote directory with the same name as localDir is located.
   * @throws RuntimeException if the rsync command fails (non-zero exit code)
   */
  def pullDir(localDir: Path, args: SOCTArgs): Unit = {
    if (!validate(args)) return
    val sshHost = args.openSSHConfig.get
    val remoteRoot = args.remoteDir.get
    localDir.toFile.mkdirs()
    val dirName = localDir.getFileName.toString
    val remoteSrc = s"$sshHost:$remoteRoot/$dirName/"
    val localDst = s"${localDir.toAbsolutePath}/"
    rsync(Seq("rsync", "-az", "--progress", "--stats", remoteSrc, localDst), s"pull $remoteSrc -> $localDst")
  }
}


object SOCTUtils {
  private def findConfigSubclasses(pkg: String = "soct"): Seq[Class[_]] = {
    import scala.jdk.CollectionConverters._
    val loader = Thread.currentThread().getContextClassLoader
    val path = pkg.replace('.', '/')
    val resources = loader.getResources(path).asScala
    val classes = resources.flatMap { url =>
      val dir = new java.io.File(url.toURI)
      if (dir.exists && dir.isDirectory) {
        dir.listFiles.filter(_.getName.endsWith(".class")).flatMap { file =>
          val name = pkg + "." + file.getName.stripSuffix(".class")
          Try(Class.forName(name)).toOption
        }
      } else Seq.empty
    }
    classes.filter(c => classOf[Config].isAssignableFrom(c) && c != classOf[Config]).toSeq
  }

  private def editDistance(a: String, b: String): Int = {
    val dp = Array.tabulate(a.length + 1, b.length + 1) { (i, j) =>
      if (i == 0) j else if (j == 0) i else 0
    }
    for (i <- 1 to a.length; j <- 1 to b.length) {
      dp(i)(j) = if (a(i - 1) == b(j - 1)) dp(i - 1)(j - 1)
      else 1 + Seq(dp(i - 1)(j), dp(i)(j - 1), dp(i - 1)(j - 1)).min
    }
    dp(a.length)(b.length)
  }


  /**
   * Instantiate a Config subclass given its name
   *
   * @param configName The fully qualified name of the Config subclass
   * @return The instantiated Config
   * @throws RuntimeException if the config cannot be instantiated, with a suggestion for the closest matching config name
   */
  def instantiateConfig(configName: String): Config = {
    try {
      Class.forName(configName).getDeclaredConstructor().newInstance().asInstanceOf[Config]
    } catch {
      case _: Exception =>
        val configs = findConfigSubclasses()
        val names = configs.map(_.getName)
        val closest = names.minBy(n => editDistance(n, configName))
        throw new RuntimeException(s"Failed to instantiate config: $configName. Did you mean: $closest?")
    }
  }

  /**
   * Generate a config name string based on the config class name and xlen, used for output directories
   */
  def configName[T <: org.chipsalliance.cde.config.Config](config: T, xLen: Int): String = {
    s"${config.getClass.getSimpleName.stripSuffix("$")}-${xLen}"
  }

  /**
   * Generate a config name string based on the config class name and xlen, used for output directories
   */
  def configName(config: Parameters, xLen: Int): String = {
    s"${config.getClass.getSimpleName.stripSuffix("$")}-${xLen}"
  }

  /**
   * Generate a config name string based on the config class name and xlen, used for output directories
   */
  def configName(configClass: Class[_ <: Config], xLen: Int): String = {
    s"${configClass.getSimpleName.stripSuffix("$")}-${xLen}"
  }

  /**
   * Run a CMake command with the given defines and working directory, returning the stdout and stderr as strings. Throws an exception if the command fails (non-zero exit code), including stdout and stderr in the exception message for debugging.
   *
   * @param command      The CMake command to run, as a sequence of strings (e.g. Seq("--build", ".", "--target", "foo"))
   * @param definesMap   A map of CMake defines to pass to the command (e.g. Map("SOCT_SYSTEM" -> "path/to/SOCTSystem.cmake")). These will be converted to -D flags (e.g. -DSOCT_SYSTEM=path/to/SOCTSystem.cmake)
   * @param workingDir   The working directory to run the command in (default: project root)
   * @param streamOutput If true, stream the command's stdout and stderr to the console in real time while also capturing it. If false, only capture the output and print it if the command fails.
   * @return A tuple of (stdout, stderr) from the command
   */
  def runCMakeCommand(command: Seq[String],
                      definesMap: Map[String, String],
                      workingDir: Path = SOCTPaths.projectRoot,
                      streamOutput: Boolean = false
                     ): (String, String) = {
    val defines = definesMap.flatMap { case (k, v) => Seq("-D", s"$k=$v") }.toSeq
    val fullCommand = Seq("cmake") ++ defines ++ command
    log.debug(s"Running CMake command: ${fullCommand.mkString(" ")} in directory: $workingDir")
    val processBuilder = new ProcessBuilder(fullCommand: _*)
      .directory(workingDir.toFile)

    val process = processBuilder.start()
    if (streamOutput) {
      // Stream output in real time while also capturing it
      val stdoutBuilder = new StringBuilder
      val stderrBuilder = new StringBuilder

      val stdoutThread = new Thread(() => {
        scala.io.Source.fromInputStream(process.getInputStream).getLines().foreach { line =>
          println(line)
          stdoutBuilder.append(line).append("\n")
        }
      })

      val stderrThread = new Thread(() => {
        scala.io.Source.fromInputStream(process.getErrorStream).getLines().foreach { line =>
          System.err.println(line)
          stderrBuilder.append(line).append("\n")
        }
      })

      stdoutThread.start()
      stderrThread.start()

      val exitCode = process.waitFor()
      stdoutThread.join()
      stderrThread.join()

      if (exitCode != 0) {
        throw new RuntimeException(s"CMake command failed with exit code $exitCode\nstderr: ${stderrBuilder.toString()}\nstdout: ${stdoutBuilder.toString()}")
      }

      (stdoutBuilder.toString(), stderrBuilder.toString())

    } else {
      val stdout = scala.io.Source.fromInputStream(process.getInputStream).mkString
      val stderr = scala.io.Source.fromInputStream(process.getErrorStream).mkString

      val exitCode = process.waitFor()
      if (exitCode != 0) {
        throw new RuntimeException(s"CMake command failed with exit code $exitCode\nstderr: $stderr\nstdout: $stdout")
      }

      (stdout, stderr)
    }
  }

  def isWindows: Boolean = {
    System.getProperty("os.name").toLowerCase.contains("win")
  }

  /**
   * Check if using Berkeley Chisel 3 API (versions 3.x)
   *
   * @return True if using Chisel 3.x, false otherwise
   */
  def isOldChiselAPI: Boolean = {
    chisel3.BuildInfo.version.startsWith("3.")
  }

  /**
   * Find the firtool binary for the given version using firtoolresolver
   *
   * @param firtoolVersion The version of firtool to find
   * @return Path to the firtool binary
   * @throws RuntimeException if firtool cannot be found
   */
  def findFirtool(firtoolVersion: String): Path = {
    val firtoolPathOpt = firtoolresolver.Resolve(firtoolVersion)
    firtoolPathOpt match {
      case Right(FirtoolBinary(path, _)) =>
        path.toPath
      case Left(error) =>
        throw new RuntimeException(s"Failed to find firtool: $error")
    }
  }

  /**
   * Print the help message of the firtool binary at the given path and exit with the same code as the firtool process.
   *
   * @param firtoolBinaryPath The path to the firtool binary to invoke with --help
   */
  def printFirtoolHelp(firtoolBinaryPath: String): Unit = {
    log.info(s"Using firtool binary: $firtoolBinaryPath")
    val code = new ProcessBuilder(firtoolBinaryPath, "--help")
      .inheritIO()
      .start()
      .waitFor()
    sys.exit(code)
  }
}
