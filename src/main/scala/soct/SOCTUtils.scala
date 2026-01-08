package soct

import firtoolresolver.FirtoolBinary
import org.chipsalliance.cde.config.Config
import org.json4s.{CustomSerializer, JNull, JString}

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.Comparator
import scala.util.Try


// JSON4S serializer for java.nio.file.Path
object PathSerializer extends CustomSerializer[Path](_ => (
  { case JString(s) => Paths.get(s) },
  { case p: Path => JString(p.toString)}
))

object TargetsSerializer extends CustomSerializer[Targets]( _ => (
  { case JString(s) => Targets.parse(s)
    case JNull      => Targets.Verilator},
  {
    case t: Targets => JString(t.name)
  }
))

// Internal Bug exception with stacktrace and message
class InternalBugException(message: String) extends Exception(message) {
  override def toString: String = s"InternalBugException: $message\n${getStackTrace.mkString("\n")}"
}


object SOCTUtils {

  /**
   * Instantiate a Config subclass given its name
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


  def runCMakeCommand(command: Seq[String],
                              definesMap: Map[String, String],
                              workingDir: Path = SOCTPaths.projectRoot
                             ): (String, String) = {
    val defines = definesMap.flatMap { case (k, v) => Seq("-D", s"$k=$v") }.toSeq
    val fullCommand = Seq("cmake") ++ defines ++ command
    log.debug(s"Running CMake command: ${fullCommand.mkString(" ")} in directory: $workingDir")
    val processBuilder = new ProcessBuilder(fullCommand: _*)
      .directory(workingDir.toFile)

    val process = processBuilder.start()

    val stdout = scala.io.Source.fromInputStream(process.getInputStream).mkString
    val stderr = scala.io.Source.fromInputStream(process.getErrorStream).mkString

    val exitCode = process.waitFor()
    if (exitCode != 0) {
      throw new RuntimeException(s"CMake command failed with exit code $exitCode\nstderr: $stderr")
    }

    (stdout, stderr)
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
   * @return True if using Chisel 3.x, false otherwise
   */
  def isOldChiselAPI: Boolean = {
    chisel3.BuildInfo.version.startsWith("3.")
  }

  /**
   * Find the firtool binary for the given version using firtoolresolver
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

  def printFirtoolHelp(firtoolBinaryPath: String): Unit = {
    log.info(s"Using firtool binary: $firtoolBinaryPath")
    val code = new ProcessBuilder(firtoolBinaryPath, "--help")
      .inheritIO()
      .start()
      .waitFor()
    sys.exit(code)
  }

  def rmrfOpt(dir: Path): Int = {
    var count = 0
    if (Files.exists(dir)) {
      Files.walk(dir)
        .sorted(Comparator.reverseOrder())
        .forEach(path => {
          if (Files.deleteIfExists(path)) count += 1
        })
    }
    count
  }

  def recCopy(src: Path, dest: Path): Int = {
    var count = 0
    Files.walk(src).forEach { p =>
      val target = dest.resolve(src.relativize(p))
      if (Files.isDirectory(p)) {
        if (!Files.exists(target)) {
          Files.createDirectory(target)
          count += 1
        }
      } else {
        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
        count += 1
      }
    }
    count
  }

  def disableStdErr(): Unit = {
    // Disable standard error output
    System.setErr(new java.io.PrintStream(new java.io.OutputStream() {
      override def write(b: Int): Unit = {}
    }))
  }

  def disableStdOut(): Unit = {
    // Disable standard output
    System.setOut(new java.io.PrintStream(new java.io.OutputStream() {
      override def write(b: Int): Unit = {}
    }))
  }

  def enableStdErr(): Unit = {
    // Enable standard error output
    System.setErr(System.err)
  }

  def enableStdOut(): Unit = {
    // Enable standard output
    System.setOut(System.out)
  }

  /**
   * Find the Verilator installation using the FindVERILATOR.cmake script
   * @return Option containing the path to the Verilator binary and root directory, or None if not found
   */
  def findVerilator(): Option[(Path, Path)] = {
    // We use Cmake in scripting mode to find the Verilator installation - no need to do the work twice and have multiple copies of filepaths
    val (stdout, _) = runCMakeCommand(Seq("-P", SOCTPaths.get("FindVERILATOR.cmake").toString), Map.empty)
    val lines = stdout.split("\n")
    val exeString = "VERILATOR_EXE: "
    val rootString = "VERILATOR_ROOT: "
    val exeOpt = lines.find(_.contains(exeString))
    val rootOpt = lines.find(_.contains(rootString))
    if (exeOpt.isDefined && rootOpt.isDefined) {
      val exeLine = exeOpt.get
      val rootLine = rootOpt.get
      val exe = exeLine.substring(exeOpt.get.indexOf(exeString) + exeString.length).trim
      val root = rootLine.substring(rootOpt.get.indexOf(rootString) + rootString.length).trim
      Some(Paths.get(exe), Paths.get(root))
    } else {
      None
    }
  }
}
