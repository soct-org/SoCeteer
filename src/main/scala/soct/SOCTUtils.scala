package soct

import firtoolresolver.FirtoolBinary
import org.chipsalliance.cde.config.{Config, Parameters}
import org.json4s.{CustomSerializer, JNull, JString}

import java.nio.file.{Files, LinkOption, Path, Paths, StandardCopyOption}
import java.util.Comparator
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Try
import scala.util.control.NonFatal


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


  // Copy Gemmini software files to the system directory
  /*
  def copyGemminiSoftware(header: String): Unit = {
    val srcDir = SOCTPaths.projectRoot.resolve("generators").resolve("gemmini").resolve("software").resolve("gemmini-rocc-tests")
    val srcInclude = srcDir.resolve("include")

    val destDir = currentSoCPaths.get.systemDir.resolve("gemmini-rocc-tests")
    val destInclude = destDir.resolve("include")
    // Create include directory if it doesn't exist
    Files.createDirectories(destInclude)
    SOCTUtils.recCopy(srcInclude, destInclude)

    val destParams = destInclude.resolve("gemmini_params.h")
    Files.write(destParams, header.getBytes(StandardCharsets.UTF_8))

    // Gemmini includes rocc-software/scr/xcustom.h so we copy that as well
    val srcXCustom = srcDir.resolve("rocc-software").resolve("src").resolve("xcustom.h")
    val destXCustom = destDir.resolve("rocc-software").resolve("src").resolve("xcustom.h")
    Files.createDirectories(destXCustom.getParent)
    Files.copy(srcXCustom, destXCustom, StandardCopyOption.REPLACE_EXISTING)
  }
   */

  def isWindows: Boolean = {
    System.getProperty("os.name").toLowerCase.contains("win")
  }

  def isUnix: Boolean = {
    System.getProperty("os.name").toLowerCase.contains("nix") || System.getProperty("os.name").toLowerCase.contains("nux")
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
