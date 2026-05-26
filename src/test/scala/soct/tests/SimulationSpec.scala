package soct.tests

import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import soct.SOCTNames.{SOCT_SIMULATOR_EXE, SOCT_SYSTEM_CMAKE_KEY, SYSCALL_TEST_BINARY}
import soct.{SOCTLauncher, SOCTPaths, SOCTPathsBase, SOCTUtils, configureLogging, logLevels}
import java.nio.file.Path

// Must be top-level (not an inner class) so json4s can instantiate it via reflection.
private case class MatrixEntry(name: String, xlen: Int)


class SimulationSpec extends AnyFlatSpec {

  implicit val formats: Formats = DefaultFormats

  val testWorkspace: Path = SOCTPaths.get("test-workspace")

  // ── Matrix loader ─────────────────────────────────────────────────────────
  //
  // Reads a JSON array of {name, xlen} objects from .github/configs/.
  // The JSON files are the single source of truth shared with the CI matrix.


  private def loadMatrix(pathKey: String): Seq[(String, Int)] = {
    val file = SOCTPaths.get(pathKey, create = false)
    val src = scala.io.Source.fromFile(file.toFile)
    val content = try src.mkString finally src.close()
    parse(content).extract[List[MatrixEntry]].map(e => (e.name, e.xlen))
  }

  /** Full CI matrix — driven by `.github/configs/full-matrix.json`. */
  val allTests: Seq[(String, Int)] = loadMatrix("full-matrix")

  // ── Core test helper ──────────────────────────────────────────────────────

  var firstRun = true

  /**
   * Build the simulator + test binary for the given config name and xlen,
   * then run the syscall-test ELF and assert a zero exit code.
   * Config is passed as --config soct.<name> directly to SOCTLauncher — no reflection needed.
   */
  def runTest(config: String, xlen: Int, logLevel: String = logLevels(1)): Unit = {
    val name = SOCTUtils.configName(config, xlen)
    val outDir = testWorkspace.resolve(name)
    val paths = SOCTPathsBase(outDir)

    SOCTLauncher.main(Array(
      "--ll", "error",
      "--config", s"soct.$config",
      "--xlen", xlen.toString,
      "--out-dir", outDir.toString,
      "-t", "verilator",
      "--no-latest-soct-system",
    ))

    withClue(s"Expected `${paths.soctSystemCMakeFile}` to exist. ") {
      assert(paths.soctSystemCMakeFile.toFile.exists())
    }

    configureLogging(logLevel)
    val defs = Map(SOCT_SYSTEM_CMAKE_KEY -> paths.soctSystemCMakeFile.toString, "CMAKE_BUILD_TYPE" -> "Release")
    val simBuildDir = paths.buildDir.resolve("sim-build")
    simBuildDir.toFile.mkdirs()

    soct.log.info(s"Configuring and building simulator in `$simBuildDir`...")
    SOCTUtils.runCMakeCommand(
      Seq("-S", SOCTPaths.get("sim").toString, "-B", simBuildDir.toString, "-G", "Ninja"),
      defs ++ Map("VL_THREADS" -> "1"),
      streamOutput = firstRun,
    )
    firstRun = false

    val (simBuildOut, simBuildErr) = SOCTUtils.runCMakeCommand(Seq("--build", simBuildDir.toString, "--verbose"), Map.empty)
    soct.log.debug(s"CMake build stdout (Simulator):\n$simBuildOut")
    soct.log.debug(s"CMake build stderr (Simulator):\n$simBuildErr")

    val simBinary = simBuildDir.resolve(SOCT_SIMULATOR_EXE + (if (SOCTUtils.isWindows) ".exe" else ""))
    withClue(s"Expected simulator binary `$simBinary` to exist after building. ") {
      assert(simBinary.toFile.exists())
    }

    val binBuildDir = paths.buildDir.resolve("prog-build")
    binBuildDir.toFile.mkdirs()
    soct.log.info(s"Configuring and building test binary in `$binBuildDir`...")

    val (binCfgOut, binCfgErr) = SOCTUtils.runCMakeCommand(
      Seq("-S", SOCTPaths.get("binaries").toString, "-B", binBuildDir.toString, "-G", "Ninja"), defs)
    soct.log.debug(s"CMake configure stdout (Test Binary):\n$binCfgOut")
    soct.log.debug(s"CMake configure stderr (Test Binary):\n$binCfgErr")

    val (binBuildOut, binBuildErr) = SOCTUtils.runCMakeCommand(
      Seq("--build", binBuildDir.toString, "--target", SYSCALL_TEST_BINARY), Map.empty)
    soct.log.debug(s"CMake build stdout (Test Binary):\n$binBuildOut")
    soct.log.debug(s"CMake build stderr (Test Binary):\n$binBuildErr")

    val testElf = paths.elfsDir.resolve(s"$SYSCALL_TEST_BINARY.elf")
    withClue(s"Expected test ELF `$testElf` to exist after building. ") {
      assert(testElf.toFile.exists())
    }

    val tgtDir = simBuildDir.resolve("syscall-test-tgt")
    tgtDir.toFile.mkdirs()
    soct.log.info(s"Running simulator `$simBinary` with ELF `$testElf`...")

    val simProcess = new ProcessBuilder(simBinary.toString, testElf.toString, s"--tgt=$tgtDir", "--log-level=debug")
      .directory(simBuildDir.toFile)
      .redirectErrorStream(true)
      .start()

    // Feed one line to stdin in a background thread to avoid deadlocking against the stdout drain below.
    val stdinFeeder = new Thread(() => {
      val writer = new java.io.PrintWriter(simProcess.getOutputStream)
      writer.println("Hello from SoCeteer!")
      writer.flush()
      writer.close()
    })
    stdinFeeder.setDaemon(true)
    stdinFeeder.start()

    // Drain stdout/stderr in a background thread so the pipe buffer never fills.
    val outputBuf = new java.util.concurrent.atomic.AtomicReference("")
    val stdoutDrainer = new Thread(() => {
      val src = scala.io.Source.fromInputStream(simProcess.getInputStream)
      outputBuf.set(src.mkString)
    })
    stdoutDrainer.setDaemon(true)
    stdoutDrainer.start()

    val simExitCode = simProcess.waitFor()
    stdinFeeder.join()
    stdoutDrainer.join()

    withClue(
      s"""Expected simulator to exit with code 0.
         |Simulator output:
         |${outputBuf.get()}
         |""".stripMargin
    ) {
      simExitCode shouldBe 0
    }
  }

  // ── Per-config test cases (used by CI matrix) ─────────────────────────────
  //
  // Each entry in allTests is registered as its own ScalaTest case so that
  // GitHub Actions can surface exactly which core failed.  CI targets one with:
  //   sbt "testOnly soct.tests.SimulationSpec -- -t \"RocketB2-64 should run without errors\""
  for ((config, xlen) <- allTests) {
    val testName = SOCTUtils.configName(config, xlen)
    testName should "run without errors" in {
      withClue(s"Config `$testName`: ") {
        runTest(config, xlen)
      }
    }
  }
}