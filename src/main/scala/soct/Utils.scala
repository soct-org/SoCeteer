package soct

import firtoolresolver.FirtoolBinary
import org.chipsalliance.cde.config.Config
import org.json4s.{CustomSerializer, JString}

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.Comparator
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Try
import scala.util.chaining.scalaUtilChainingOps
import scala.util.matching.Regex
import soct.RocketLauncher.currentSoCPaths

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

object DTSExtractor {

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
}

object DTSModifier {

  // Replace 32-bit memory address range
  private def updateMemoryRange32(dts: String, memoryAddrRange32: String): String = {
    val pattern: Regex = """reg = <0x80000000 *0x.*?>""".r
    pattern.replaceAllIn(dts, s"reg = <$memoryAddrRange32>")
  }

  // Replace 64-bit memory address range
  private def updateMemoryRange64(dts: String, memoryAddrRange64: String): String = {
    val pattern: Regex = """reg = <0x0 0x80000000 *0x.*?>""".r
    pattern.replaceAllIn(dts, s"reg = <$memoryAddrRange64>")
  }

  // Update clock frequency
  private def updateClockFrequency(dts: String, rocketClockFreq: String): String = {
    val pattern: Regex = """clock-frequency = <[0-9]+>""".r
    pattern.replaceAllIn(dts, s"clock-frequency = <$rocketClockFreq>")
  }

  // Update timebase frequency
  private def updateTimebaseFrequency(dts: String, rocketTimebaseFreq: String): String = {
    val pattern: Regex = """timebase-frequency = <[0-9]+>""".r
    pattern.replaceAllIn(dts, s"timebase-frequency = <$rocketTimebaseFreq>")
  }

  // Update Ethernet MAC address (if defined)
  private def updateEtherMacAddress(dts: String, etherMac: Option[String]): String = {
    etherMac match {
      case Some(mac) =>
        val pattern: Regex = """local-mac-address = \[.*?]""".r
        pattern.replaceAllIn(dts, s"local-mac-address = [$mac]")
      case None => dts
    }
  }

  // Update Ethernet PHY mode (if defined)
  private def updateEtherPhyMode(dts: String, etherPhy: Option[String]): String = {
    etherPhy match {
      case Some(phy) =>
        val pattern: Regex = """phy-mode = ".*?" """.r
        pattern.replaceAllIn(dts, s"""phy-mode = "$phy" """)
      case None => dts
    }
  }

  // Remove unwanted interrupt settings
  private def removeInterruptsExtended(dts: String): String = {
    val pattern: Regex = """\s*interrupts-extended = <&.*? 65535>;\s*\n?""".r
    pattern.replaceAllIn(dts, "")
  }

  // Apply all modifications
  def modifyDTS(dts: String, params: BoardParams): String = {
    val (rocketClockFreq, rocketTimebaseFreq) = params.calculateFrequencies()
    val (memoryAddrRange32, memoryAddrRange64) = params.calculateMemoryAddressRange()

    val updatedDTS = updateMemoryRange32(dts, memoryAddrRange32)
      .pipe(updateMemoryRange64(_, memoryAddrRange64))
      .pipe(updateClockFrequency(_, rocketClockFreq.toString))
      .pipe(updateTimebaseFrequency(_, rocketTimebaseFreq.toString))
      .pipe(updateEtherMacAddress(_, params.ETHER_MAC))
      .pipe(updateEtherPhyMode(_, params.ETHER_PHY))
      .pipe(removeInterruptsExtended)

    updatedDTS
  }
}

// JSON4S serializer for java.nio.file.Path
object PathSerializer extends CustomSerializer[Path](format => (
  { case JString(s) => Paths.get(s) },
  { case p: Path => JString(p.toString) }
))


object Utils {

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

  def projectRoot(): Path = {
    // Check if defined in environment variable
    val envVar = System.getenv("SOCETEER_ROOT")
    if (envVar != null) {
      val path = Paths.get(envVar)
      if (Files.exists(path)) {
        return path
      }
    }
    Paths.get("").toAbsolutePath
  }

  def copyGemminiSoftware(header: String): Unit = {
    val srcDir = Utils.projectRoot().resolve("generators").resolve("gemmini").resolve("software").resolve("gemmini-rocc-tests")
    val srcInclude = srcDir.resolve("include")

    val destDir = currentSoCPaths.get.systemDir.resolve("gemmini-rocc-tests")
    val destInclude = destDir.resolve("include")
    // Create include directory if it doesn't exist
    Files.createDirectories(destInclude)
    Utils.recCopy(srcInclude, destInclude)

    val destParams = destInclude.resolve("gemmini_params.h")
    Files.write(destParams, header.getBytes(StandardCharsets.UTF_8))

    // Gemmini includes rocc-software/scr/xcustom.h so we copy that as well
    val srcXCustom = srcDir.resolve("rocc-software").resolve("src").resolve("xcustom.h")
    val destXCustom = destDir.resolve("rocc-software").resolve("src").resolve("xcustom.h")
    Files.createDirectories(destXCustom.getParent)
    Files.copy(srcXCustom, destXCustom, StandardCopyOption.REPLACE_EXISTING)
  }

  def synPath(): Path = {
    Utils.projectRoot().resolve("syn")
  }

  def boardsPath(): Path = {
    Utils.synPath().resolve("boards")
  }

  def tclSrcsPath(): Path = {
    Utils.synPath().resolve("tclsrcs")
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

  def enableStdErr(): Unit = {
    // Enable standard error output
    System.setErr(System.err)
  }

  /**
   * Install the RISC-V toolchain using CMake and return the absolute path and the prefix (without the final "-")
   *
   * @return Path to the RISC-V toolchain and the prefix
   * @throws Exception if the installation fails or the output cannot be parsed
   */
  def installRiscVToolchain(): String = {
    // We use CMake in scripting mode to install the RISC-V toolchain - no need to do the work twice and have multiple copies of filepaths
    val cmd = new ProcessBuilder("cmake", "-P", projectRoot().resolve("buildtools").resolve("cmake").resolve("riscv-toolchain-shipped.cmake").toString)
      .directory(projectRoot().toFile)
      .redirectErrorStream(true)
      .start()
    val exitCode = cmd.waitFor()
    if (exitCode != 0) {
      throw new Exception(s"Failed to install RISC-V toolchain - exit code: $exitCode")
    }
    val searchString = "RISC-V toolchain prefix: "
    val output = scala.io.Source.fromInputStream(cmd.getInputStream).getLines()
    // Read the output line by line and find the line that contains the search string
    val lineOpt = output.find(_.contains(searchString))
    if (lineOpt.isDefined) {
      val line = lineOpt.get
      val prefix = line.substring(line.indexOf(searchString) + searchString.length).trim
      log.info(s"$searchString$prefix")
      prefix
    } else {
      throw new Exception(s"Failed to install RISC-V toolchain - output: $output")
    }
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
    val cmd = new ProcessBuilder("cmake", "-P", projectRoot().resolve("buildtools").resolve("cmake").resolve("FindVerilator.cmake").toString)
      .directory(projectRoot().toFile)
      .redirectErrorStream(true)
      .start()
    val exitCode = cmd.waitFor()
    if (exitCode != 0) {
      log.error("Failed to find Verilator - exit code: " + exitCode)
      return None
    }
    val searchString = "Verilator installation: "
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
      log.error("Failed to find Verilator - output: " + output.mkString("\n"))
      None
    }
  }
}
