package soct

import firtoolresolver.FirtoolBinary
import org.chipsalliance.cde.config.{Config, Parameters}
import org.json4s.{CustomSerializer, JNull, JString}
import soct.SOCTBytes.{ByteUnitOpsInt, Bytes}
import soct.SOCTUtils.MAX_MEM_SIZE_32_BIT
import soct.system.vivado.VivadoDesignException
import soct.system.vivado.fpga.{PartRegistry, DDR4PortParams}

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
    log.info(s"Syncing $what with command: ${cmd.mkString(" ")}")
    val exitCode = new ProcessBuilder(cmd: _*)
      .redirectOutput(if (log.underlying.isDebugEnabled) ProcessBuilder.Redirect.INHERIT else ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .start().waitFor()
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
  /**
   * The maximum memory size for 32-bit address space, which is 2 GiB. This is used to ensure that the memory size does not exceed the addressable range for 32-bit systems.
   * Very hacky for now
   */
  val MAX_MEM_SIZE_32_BIT: SOCTBytes.Bytes = 2.GiB


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
   * Generate a config name string from a plain class simple name and xlen, used for output directories
   */
  def configName(configClassName: String, xLen: Int): String = {
    s"$configClassName-$xLen"
  }

  /**
   * Run a CMake command with the given defines and working directory, returning the stdout and stderr as strings. Throws an exception if the command fails (non-zero exit code), including stdout and stderr in the exception message for debugging.
   *
   * @param command      The CMake command to run, as a sequence of strings (e.g. Seq("--build", ".", "--target", "foo"))
   * @param definesMap   A map of CMake defines to pass to the command (e.g. Map("SOCT_SYSTEM" -> "path/to/SOCTSystem.cmake")). These will be converted to -D flags (e.g. -DSOCT_SYSTEM=path/to/SOCTSystem.cmake)
   * @param workingDir   The working directory to run the command in (default: project root)
   * @param streamOutput If true, stream the command's stdout and stderr to the console in real time while also capturing it. If false, only capture the output and print it if the command fails.
   * @return A tuple of (stdout, stderr) from the command
   * @throws RuntimeException If the command fails (non-zero exit code), with stdout and stderr included in the exception message for debugging
   */
  def runCMakeCommand(command: Seq[String],
                      definesMap: Map[String, String] = Map.empty,
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

  /** True when running on Windows (used to pick CMake generators and path handling). */
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


  /** Returns true iff x is a positive power of two. */
  def isPowerOfTwo(x: Long): Boolean =
    x > 0 && (x & (x - 1)) == 0

  /** Exact log2 for powers of two.
   *
   * Example:
   * log2Exact(4096) == 12
   *
   * @throws IllegalArgumentException if x is not a positive power of two
   */
  def log2Exact(x: Long): Int = {
    require(isPowerOfTwo(x), s"$x is not a positive power of two")
    java.lang.Long.numberOfTrailingZeros(x)
  }

  /** Formats a Long as an uppercase hexadecimal literal.
   *
   * Example:
   * toHex(4096) == "0x1000"
   */
  def toHex(x: Long): String =
    f"0x${x}%X"

  /** Formats a value using the largest unit whose scale is <= value.
   *
   * Example:
   * scaledString(
   * 2147483648.0,
   * Seq(("GiB", 1L << 30), ("MiB", 1L << 20), ("B", 1.0))
   * )
   * // "2.00 GiB"
   */
  def scaledString(
                    value: Double,
                    units: Seq[(String, Double)],
                    decimals: Int = 2
                  ): String = {
    val (unit, scale) =
      units.find { case (_, s) => value >= s }
        .getOrElse(units.last)

    String.format(s"%.${decimals}f %s", Double.box(value / scale), unit)
  }
}


/** SOCTBytes
 *
 * Convenience constants/conversions for working with byte counts in SoC /
 * address-map contexts: KiB/MiB/GiB/TiB (binary, IEC) and KB/MB/GB/TB
 * (decimal, SI), plus helpers that are specifically useful when laying out
 * memory maps (power-of-two checks, address-bit-width derivation, hex
 * formatting). Shared low-level logic lives in SOCTUtils.
 *
 * Usage:
 * import SOCTBytes._
 *
 * val ddrSize   = 2.GiB
 * val flashSize = 512.MiB
 * val total     = ddrSize + flashSize
 *
 * println(ddrSize.addrBits)     // 31
 * println(ddrSize.toHex)        // 0x80000000
 * println(total)                // 2.50 GiB
 *
 * val mibFromGib = ddrSize.toMiB // 2048.0
 */
object SOCTBytes {

  // ---- Binary (IEC) units, in bytes ----
  final val KiB: Long = 1L << 10
  final val MiB: Long = 1L << 20
  final val GiB: Long = 1L << 30
  final val TiB: Long = 1L << 40
  final val PiB: Long = 1L << 50

  // ---- Decimal (SI) units, in bytes ----
  final val KB: Long = 1000L
  final val MB: Long = KB * 1000L
  final val GB: Long = MB * 1000L
  final val TB: Long = GB * 1000L
  final val PB: Long = TB * 1000L

  /** A byte count with convenient conversions/formatting attached.
   * Backed by Long, which comfortably covers any realistic address space
   * (up to ~8 EiB) without needing BigInt.
   */
  final case class Bytes(value: Long) extends AnyVal {

    def +(other: Bytes): Bytes = Bytes(value + other.value)

    def -(other: Bytes): Bytes = Bytes(value - other.value)

    def *(scalar: Long): Bytes = Bytes(value * scalar)

    def /(scalar: Long): Long = value / scalar

    def <(other: Bytes): Boolean = value < other.value

    def <=(other: Bytes): Boolean = value <= other.value

    def >(other: Bytes): Boolean = value > other.value

    def >=(other: Bytes): Boolean = value >= other.value

    // ---- Binary conversions (exact ratios, returned as Double for readability) ----
    def toKiB: Double = value.toDouble / KiB

    def toMiB: Double = value.toDouble / MiB

    def toGiB: Double = value.toDouble / GiB

    def toTiB: Double = value.toDouble / TiB

    def toPiB: Double = value.toDouble / PiB

    // ---- Decimal conversions ----
    def toKB: Double = value.toDouble / KB

    def toMB: Double = value.toDouble / MB

    def toGB: Double = value.toDouble / GB

    def toTB: Double = value.toDouble / TB

    def toPB: Double = value.toDouble / PB

    /** True if value is a power of two -- useful for sanity-checking
     * address-window sizes before wiring them into an address editor.
     */
    def isPowerOfTwo: Boolean = SOCTUtils.isPowerOfTwo(value)

    /** Number of address bits needed to span this many bytes.
     * Only well-defined for power-of-two sizes (the common case for
     * AXI address windows / DDR channels / MMIO apertures).
     */
    def addrBits: Int = SOCTUtils.log2Exact(value)

    /** Bytes as a hex literal, e.g. "0x80000000" -- handy for dropping
     * straight into an address-editor offset or a Tcl assign_bd_address call.
     */
    def toHex: String = SOCTUtils.toHex(value)

    /** Human-readable form, picking the largest binary unit that gives a
     * result >= 1.0 (e.g. "2.00 GiB", "512.00 MiB", "768.00 B").
     */
    def humanReadable: String = SOCTUtils.scaledString(
      value.toDouble,
      Seq(("PiB", PiB.toDouble), ("TiB", TiB.toDouble), ("GiB", GiB.toDouble),
        ("MiB", MiB.toDouble), ("KiB", KiB.toDouble), ("B", 1.0))
    )

    override def toString: String = humanReadable
  }

  implicit object BytesIsIntegral extends Integral[Bytes] {
    def quot(x: Bytes, y: Bytes): Bytes = Bytes(x.value / y.value)

    def rem(x: Bytes, y: Bytes): Bytes = Bytes(x.value % y.value)

    def plus(x: Bytes, y: Bytes): Bytes = x + y

    def minus(x: Bytes, y: Bytes): Bytes = x - y

    def times(x: Bytes, y: Bytes): Bytes = Bytes(x.value * y.value)

    def negate(x: Bytes): Bytes = Bytes(-x.value)

    def fromInt(x: Int): Bytes = Bytes(x.toLong)

    def parseString(str: String): Option[Bytes] = Bytes.parse(str)

    def toInt(x: Bytes): Int = x.value.toInt

    def toLong(x: Bytes): Long = x.value

    def toFloat(x: Bytes): Float = x.value.toFloat

    def toDouble(x: Bytes): Double = x.value.toDouble

    def compare(x: Bytes, y: Bytes): Int = java.lang.Long.compare(x.value, y.value)

    // JDK 25+ gives java.util.Comparator (which Ordering extends) max/min default methods
    // that collide with Ordering's own; explicit overrides resolve the conflict there and
    // are ordinary Ordering overrides on older JDKs.
    override def max[U <: Bytes](x: U, y: U): U = if (gteq(x, y)) x else y

    override def min[U <: Bytes](x: U, y: U): U = if (lteq(x, y)) x else y
  }

  object Bytes {
    def apply(i: Int): Bytes = Bytes(i.toLong)

    def sum(xs: Iterable[Bytes]): Bytes = Bytes(xs.foldLeft(0L)(_ + _.value))

    private val stringPattern =
      """(?i)^\s*([0-9]+(?:\.[0-9]+)?)\s*([a-z]*)\s*$""".r

    private val unitSizes: Map[String, Long] = Map(
      "" -> 1L,
      "B" -> 1L,
      "KIB" -> SOCTBytes.KiB,
      "KB" -> SOCTBytes.KB,
      "MIB" -> SOCTBytes.MiB,
      "MB" -> SOCTBytes.MB,
      "GIB" -> SOCTBytes.GiB,
      "GB" -> SOCTBytes.GB,
      "TIB" -> SOCTBytes.TiB,
      "TB" -> SOCTBytes.TB,
      "PIB" -> SOCTBytes.PiB,
      "PB" -> SOCTBytes.PB
    )

    def parse(s: String): Option[Bytes] = s match {
      case stringPattern(numStr, unitStr) =>
        unitSizes.get(unitStr.toUpperCase).map { unitBytes =>
          val exact = BigDecimal(numStr) * BigDecimal(unitBytes)
          Bytes(exact.setScale(0, BigDecimal.RoundingMode.HALF_UP).toLongExact)
        }
      case _ => None
    }

    def apply(s: String): Bytes =
      parse(s).getOrElse(
        throw new IllegalArgumentException(
          s"""Cannot parse "$s" as a byte size (expected e.g. "3.5GiB", "20000B", "512", "2GB")"""
        )
      )
  }

  /** Implicit numeric literal extensions, e.g. `4.GiB`, `512.MiB`, `2.GB`. */
  implicit class ByteUnitOpsLong(private val n: Long) extends AnyVal {
    def B: Bytes = Bytes(n)

    def KiB: Bytes = Bytes(n * SOCTBytes.KiB)

    def MiB: Bytes = Bytes(n * SOCTBytes.MiB)

    def GiB: Bytes = Bytes(n * SOCTBytes.GiB)

    def TiB: Bytes = Bytes(n * SOCTBytes.TiB)

    def PiB: Bytes = Bytes(n * SOCTBytes.PiB)

    def KB: Bytes = Bytes(n * SOCTBytes.KB)

    def MB: Bytes = Bytes(n * SOCTBytes.MB)

    def GB: Bytes = Bytes(n * SOCTBytes.GB)

    def TB: Bytes = Bytes(n * SOCTBytes.TB)

    def PB: Bytes = Bytes(n * SOCTBytes.PB)
  }

  implicit class ByteUnitOpsInt(private val n: Int) extends AnyVal {
    def B: Bytes = Bytes(n.toLong)

    def KiB: Bytes = Bytes(n.toLong * SOCTBytes.KiB)

    def MiB: Bytes = Bytes(n.toLong * SOCTBytes.MiB)

    def GiB: Bytes = Bytes(n.toLong * SOCTBytes.GiB)

    def TiB: Bytes = Bytes(n.toLong * SOCTBytes.TiB)

    def PiB: Bytes = Bytes(n.toLong * SOCTBytes.PiB)

    def KB: Bytes = Bytes(n.toLong * SOCTBytes.KB)

    def MB: Bytes = Bytes(n.toLong * SOCTBytes.MB)

    def GB: Bytes = Bytes(n.toLong * SOCTBytes.GB)

    def TB: Bytes = Bytes(n.toLong * SOCTBytes.TB)

    def PB: Bytes = Bytes(n.toLong * SOCTBytes.PB)
  }
}


/** SOCTFreq
 *
 * Convenience constants/conversions for working with clock frequencies in SoC
 * and FPGA designs: Hz/kHz/MHz/GHz/THz (decimal, SI), plus helpers that are
 * specifically useful when configuring clocks (period conversion, angular
 * frequency, and human-readable formatting). Shared low-level logic lives in
 * SOCTUtils.
 *
 * Usage:
 * import SOCTFreq._
 *
 * val cpuClk = 100.MHz
 * val axiClk = 250.MHz
 * val total  = cpuClk + axiClk
 *
 * println(cpuClk.periodNs)      // 10.0
 * println(cpuClk.toHz)          // 100000000.0
 * println(total)                // 350.00 MHz
 */
object SOCTFreq {

  // ---- SI units, in hertz ----
  final val Hz: Long = 1L
  final val kHz: Long = 1_000L
  final val MHz: Long = 1_000_000L
  final val GHz: Long = 1_000_000_000L
  final val THz: Long = 1_000_000_000_000L

  /** A frequency with convenient conversions/formatting attached.
   * Backed by Double to naturally support fractional frequencies such as
   * 33.333 MHz or 148.5 MHz.
   */
  final case class Freq(value: Double) extends AnyVal {

    def +(other: Freq): Freq = Freq(value + other.value)

    def -(other: Freq): Freq = Freq(value - other.value)

    def *(scalar: Double): Freq = Freq(value * scalar)

    def /(scalar: Double): Freq = Freq(value / scalar)

    def <(other: Freq): Boolean = value < other.value

    def <=(other: Freq): Boolean = value <= other.value

    def >(other: Freq): Boolean = value > other.value

    def >=(other: Freq): Boolean = value >= other.value

    // ---- SI conversions ----
    def toHz: Double = value

    def tokHz: Double = value / kHz

    def toMHz: Double = value / MHz

    def toGHz: Double = value / GHz

    def toTHz: Double = value / THz

    /** Clock period in seconds. */
    def periodSeconds: Double = 1.0 / value

    /** Clock period in milliseconds. */
    def periodMs: Double = 1e3 / value

    /** Clock period in microseconds. */
    def periodUs: Double = 1e6 / value

    /** Clock period in nanoseconds. */
    def periodNs: Double = 1e9 / value

    /** Clock period in picoseconds. */
    def periodPs: Double = 1e12 / value

    /** Angular frequency (ω = 2πf), in rad/s. */
    def toAngular: Double = 2.0 * math.Pi * value

    /** Human-readable form, choosing the largest SI unit with a value >= 1.0. */
    def humanReadable: String = SOCTUtils.scaledString(
      value,
      Seq(
        ("THz", THz.toDouble),
        ("GHz", GHz.toDouble),
        ("MHz", MHz.toDouble),
        ("kHz", kHz.toDouble),
        ("Hz", Hz.toDouble)
      )
    )

    override def toString: String = humanReadable
  }

  object Freq {
    def apply(i: Int): Freq = Freq(i.toDouble)

    def apply(l: Long): Freq = Freq(l.toDouble)

    /** Sum a sequence of frequencies. */
    def sum(xs: Iterable[Freq]): Freq =
      Freq(xs.foldLeft(0.0)(_ + _.value))

    implicit val ordering: Ordering[Freq] = Ordering.by(_.value)
  }

  /** Implicit numeric literal extensions, e.g. `100.MHz`, `2.5.GHz`. */
  implicit class FreqUnitOpsLong(private val n: Long) extends AnyVal {
    def Hz: Freq = Freq(n.toDouble)

    def kHz: Freq = Freq(n.toDouble * SOCTFreq.kHz)

    def MHz: Freq = Freq(n.toDouble * SOCTFreq.MHz)

    def GHz: Freq = Freq(n.toDouble * SOCTFreq.GHz)

    def THz: Freq = Freq(n.toDouble * SOCTFreq.THz)
  }

  implicit class FreqUnitOpsInt(private val n: Int) extends AnyVal {
    def Hz: Freq = Freq(n.toDouble)

    def kHz: Freq = Freq(n.toDouble * SOCTFreq.kHz)

    def MHz: Freq = Freq(n.toDouble * SOCTFreq.MHz)

    def GHz: Freq = Freq(n.toDouble * SOCTFreq.GHz)

    def THz: Freq = Freq(n.toDouble * SOCTFreq.THz)
  }

  implicit class FreqUnitOpsDouble(private val n: Double) extends AnyVal {
    def Hz: Freq = Freq(n)

    def kHz: Freq = Freq(n * SOCTFreq.kHz)

    def MHz: Freq = Freq(n * SOCTFreq.MHz)

    def GHz: Freq = Freq(n * SOCTFreq.GHz)

    def THz: Freq = Freq(n * SOCTFreq.THz)
  }
}