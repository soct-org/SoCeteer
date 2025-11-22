package soct

import firtoolresolver.FirtoolBinary
import org.chipsalliance.cde.config.Config
import org.json4s.{CustomSerializer, JNull, JString}

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.Comparator
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Try
import soct.SOCTLauncher.{Targets, currentSoCPaths}

import java.nio.charset.StandardCharsets



case class BoardParams(
                        BOARD_PART: Option[String],
                        XILINX_PART: String,
                        CFG_DEVICE: String,
                        CFG_PART: String,
                        MEMORY_SIZE: String,
                        ROCKET_FREQ_MHZ: Option[Double],
                        ETHER_MAC: Option[String],
                        ETHER_PHY: Option[String]
                      ) {
  // Calculate ROCKET_CLOCK_FREQ and ROCKET_TIMEBASE_FREQ
  def calculateFrequencies(): (Long, Long) = {
    ROCKET_FREQ_MHZ match {
      case Some(freq) =>
        val rocketClockFreq = (freq * 1000000).round
        val rocketTimebaseFreq = (freq * 10000).round
        (rocketClockFreq, rocketTimebaseFreq)
      case None =>
        throw new UnsupportedOperationException("ROCKET_FREQ_MHZ not defined")
    }
  }

  // Calculate memory address range based on MEMORY_SIZE
  def calculateMemoryAddressRange(): (String, String) = {
    // remove 0x prefix and convert to BigInt
    val memorySize = BigInt(MEMORY_SIZE.replace("0x", ""), 16)

    if (memorySize <= 0x80000000L) {
      val range32 = s"0x80000000 $memorySize"
      val range64 = s"0x0 0x80000000 0x0 $memorySize"
      (range32, range64)
    } else {
      // Not yet implemented
      throw new NotImplementedError("Memory size > 2GB not yet supported")
    }
  }
}

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

  // Instantiate a Config subclass by name with error suggestion
  def instantiateConfig(currentName: String): Config = {
    try {
      Class.forName(currentName).getDeclaredConstructor().newInstance().asInstanceOf[Config]
    } catch {
      case _: Exception =>
        val configs = findConfigSubclasses()
        val names = configs.map(_.getName)
        val closest = names.minBy(n => editDistance(n, currentName))
        throw new RuntimeException(s"Failed to instantiate config: $currentName. Did you mean: $closest?")
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


  private def runCMakeCommand(command: Seq[String], definesMap: Map[String, String], workingDir: Path = SOCTPaths.projectRoot): Unit = {
    val defines = definesMap.flatMap { case (k, v) => Seq("-D", s"$k=$v") }.toSeq
    val fullCommand = Seq("cmake") ++ defines ++ command
    log.debug(s"Running CMake command: ${fullCommand.mkString(" ")} in directory: $workingDir")
    val processBuilder = new ProcessBuilder(fullCommand: _*)
      .directory(workingDir.toFile)
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.INHERIT)

    val process = processBuilder.start()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
      throw new RuntimeException(s"CMake command failed with exit code $exitCode")
    }
  }


  /**
   * Compile the bootrom for the given configuration and artifacts using CMake
   */
  def compileBootrom(paths: SOCTPaths, artifacts: Set[Path], config: SOCTLauncher.Config,
                             boardDTS: Option[String] = None, boardParams: Option[BoardParams] = None): Path = {
    val rocketDTS = artifacts.find(_.getFileName.toString.endsWith(".dts")).getOrElse {
      throw new InternalBugException("No dts file found in artifacts") // This should never happen
    }
    var fullDTS = Files.readAllLines(rocketDTS).toArray.mkString("\n")
    if (boardDTS.isDefined && boardParams.isDefined) {
      fullDTS = DTSModifier.modifyDTS(s"$fullDTS\n${boardDTS.get}", boardParams.get)
    }

    Files.write(paths.dtsFile, fullDTS.getBytes)
    // Obtain the architecture from the dts
    val march = DTSExtractor.extractMarch(fullDTS)

    // Compile bootrom using CMake
    val defs = Map(
      "BOOTROM_MODE" -> "ON",
      "MARCH" -> march,
      "MABI" -> config.mabi,
      "DTS_PATH" -> paths.dtsFile.toString,
      "IMG_PATH" -> paths.bootromImgFile.toString,
    )

    val sourceDir = SOCTPaths.get("binaries")
    val buildDir = SOCTPaths.get("binaries-build")
    // Delete the cache to force reconfiguration
    val cacheFile = buildDir.resolve("CMakeCache.txt")
    cacheFile.toFile.delete()
    val target = config.args.bootrom.getOrElse(config.args.target.defaultBootrom)

    // Init CMake
    log.info("Building bootrom using CMake")
    runCMakeCommand(Seq("-S", sourceDir.toString, "-B", buildDir.toString), defs)
    runCMakeCommand(Seq("--build", buildDir.toString, "--target", target), Map.empty)

    assert(Files.exists(paths.bootromImgFile), s"Bootrom image file ${paths.bootromImgFile} was not created")
    paths.bootromImgFile
  }

  // Copy Gemmini software files to the system directory
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

  def isWindows: Boolean = {
    System.getProperty("os.name").toLowerCase.contains("win")
  }

  def isUnix: Boolean = {
    System.getProperty("os.name").toLowerCase.contains("nix") || System.getProperty("os.name").toLowerCase.contains("nux")
  }

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

  def listFilesWithExtension(startDir: Path, extension: String): List[java.io.File] = {
    Files.walk(startDir)
      .iterator()
      .asScala
      .filter(path => path.toString.endsWith(extension))
      .map(_.toFile)
      .toList
  }

  def findVerilator(): Option[Path] = {
    // We use Cmake in scripting mode to find the Verilator installation - no need to do the work twice and have multiple copies of filepaths
    val cmd = new ProcessBuilder("cmake", "-P", SOCTPaths.get("FindVerilator.cmake").toString)
      .directory(SOCTPaths.projectRoot.toFile)
      .redirectErrorStream(true)
      .start()
    val exitCode = cmd.waitFor()
    if (exitCode != 0) {
      log.error("Failed to find Verilator - exit code: " + exitCode)
      return None
    }
    val searchString = "VERILATOR_EXE: "
    // Read the output line by line and find the line that contains the search string
    val output = scala.io.Source.fromInputStream(cmd.getInputStream).getLines()
    // Read the output line by line and find the line that contains the search string
    val lineOpt = output.find(_.contains(searchString))
    if (lineOpt.isDefined) {
      val line = lineOpt.get
      val prefix = line.substring(line.indexOf(searchString) + searchString.length).trim
      log.info(s"Verilator installation found at $prefix")
      Some(Paths.get(prefix))
    } else {
      log.warn("Failed to find Verilator - output: " + output.mkString("\n"))
      None
    }
  }
}
