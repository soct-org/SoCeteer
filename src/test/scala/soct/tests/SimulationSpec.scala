package soct.tests

import org.chipsalliance.cde.config.Config
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import soct.SOCTNames.{SOCT_SIMULATOR_EXE, SOCT_SYSTEM_CMAKE_KEY, SYSCALL_TEST_BINARY}
import soct.{SOCTLauncher, SOCTPaths, SOCTPathsBase, SOCTUtils, configureLogging, logLevels}

class SimulationSpec extends AnyFlatSpec {

  val XLEN_32 = Seq(32)
  val XLEN_64 = Seq(64)
  val XLEN_ALL = Seq(32, 64)
  val testWorkspace = SOCTPaths.get("test-workspace")
  var firstRun = true

  case class Test(config: Class[_ <: Config], xlens: Seq[Int])

  // Boom3 not tested by default
  val boom3Tests: Seq[Test] = Seq(
    Test(classOf[soct.SmallBoomV3], XLEN_64),
    Test(classOf[soct.DualSmallBoomV3], XLEN_64),
    Test(classOf[soct.MediumBoomV3], XLEN_64),
    Test(classOf[soct.DualMediumBoomV3], XLEN_64),
    Test(classOf[soct.LargeBoomV3], XLEN_64),
    Test(classOf[soct.DualLargeBoomV3], XLEN_64),
    Test(classOf[soct.MegaBoomV3], XLEN_64),
    Test(classOf[soct.DualMegaBoomV3], XLEN_64)
  )

  val boom4Tests: Seq[Test] = Seq(
    Test(classOf[soct.SmallBoomV4], XLEN_64),
    Test(classOf[soct.DualSmallBoomV4], XLEN_64),
    Test(classOf[soct.MediumBoomV4], XLEN_64),
    Test(classOf[soct.DualMediumBoomV4], XLEN_64),
    Test(classOf[soct.LargeBoomV4], XLEN_64),
    Test(classOf[soct.DualLargeBoomV4], XLEN_64),
    Test(classOf[soct.MegaBoomV4], XLEN_64),
    Test(classOf[soct.DualMegaBoomV4], XLEN_64)
  )

  val rocketTests: Seq[Test] = Seq(
    Test(classOf[soct.RocketS1], XLEN_ALL),
    Test(classOf[soct.RocketS2], XLEN_ALL),
    Test(classOf[soct.RocketM1], XLEN_ALL),
    Test(classOf[soct.RocketM2], XLEN_ALL),
    Test(classOf[soct.RocketB1], XLEN_ALL),
    Test(classOf[soct.RocketB2], XLEN_ALL),
    Test(classOf[soct.RocketH1], XLEN_ALL),
    Test(classOf[soct.RocketH2], XLEN_ALL)
  )

  val gemminiTests: Seq[Test] = Seq(
    Test(classOf[soct.RocketB1Gem4Fp], XLEN_64),
    Test(classOf[soct.RocketB1Gem4], XLEN_64)
  )


  val allTests: Seq[Test] = gemminiTests ++ boom4Tests ++ rocketTests


  /**
   * Run a test with the given configuration and xlen.
   * This generates the SOCTSystem.cmake file for the test configuration, then configures and builds both the simulator and the test binary using that file, and finally runs the simulator with the test ELF.
   */
  def runTest(config: Class[_ <: Config], xlen: Int, logLevel: String = logLevels(1)): Unit = {
    val outDir = testWorkspace.resolve(SOCTUtils.configName(config, xlen))
    val paths = SOCTPathsBase(outDir)
    val args = Seq(
      "--ll", "error",
      "--config", config.getCanonicalName,
      "--xlen", xlen.toString,
      "--out-dir", outDir.toString,
      "-t", "verilator",
      "--no-latest-soct-system" // Don't create symlink to latest SOCTSystem.cmake file for tests, to avoid conflicts between tests and user builds
    )
    SOCTLauncher.main(args.toArray)

    withClue(s"Expected `${paths.soctSystemCMakeFile}` to exist. ") {
      assert(paths.soctSystemCMakeFile.toFile.exists())
    }

    configureLogging(logLevel)
    val defs = Map(
      SOCT_SYSTEM_CMAKE_KEY -> paths.soctSystemCMakeFile.toString,
      "CMAKE_BUILD_TYPE" -> "Release",
    )
    val simBuildDir = paths.buildDir.resolve("sim-build")
    simBuildDir.toFile.mkdirs()

    soct.log.info(s"Configuring and building simulator in `${simBuildDir}` with SOCTSystem.cmake at `${paths.soctSystemCMakeFile}`...")
    // Configure and build the simulator in the test build directory, using the generated SOCTSystem.cmake file
    // Builds verilator on the first run, which can take a long time, so stream output to show the user that something is happening.
    SOCTUtils.runCMakeCommand(
      Seq("-S", SOCTPaths.get("sim").toString, "-B", simBuildDir.toString, "-G", "Ninja"),
      defs ++ Map("VL_THREADS" -> "1"), // Disable verilator multithreading to avoid issues on GitHub Actions runners with limited resources
      streamOutput = firstRun
    )
    firstRun = false
    val (simBuildStdout, simBuildStderr) =
      SOCTUtils.runCMakeCommand(
        Seq("--build", simBuildDir.toString, "--verbose"),
        Map.empty,
      )
    soct.log.debug(s"CMake build stdout (Simulator):\n$simBuildStdout")
    soct.log.debug(s"CMake build stderr (Simulator):\n$simBuildStderr")

    // Validate that the simulator binary was created:
    val simBinary = simBuildDir.resolve(SOCT_SIMULATOR_EXE + (if (SOCTUtils.isWindows) ".exe" else ""))
    withClue(s"Expected simulator binary `${simBinary}` to exist after building. ") {
      assert(simBinary.toFile.exists())
    }

    // Now configure and build the test binary using the same SOCTSystem.cmake file, but with a separate build directory
    val binBuildDir = paths.buildDir.resolve("prog-build")
    binBuildDir.toFile.mkdirs()

    soct.log.info(s"Configuring and building test binary in `$binBuildDir` with SOCTSystem.cmake at `${paths.soctSystemCMakeFile}`...")

    val (binCfgStdout, binCfgStderr) = SOCTUtils.runCMakeCommand(
      Seq("-S", SOCTPaths.get("binaries").toString, "-B", binBuildDir.toString, "-G", "Ninja"),
      defs
    )
    soct.log.debug(s"CMake configure stdout (Test Binary):\n$binCfgStdout")
    soct.log.debug(s"CMake configure stderr (Test Binary):\n$binCfgStderr")

    val (binBuildStdout, binBuildStderr) =
      SOCTUtils.runCMakeCommand(
        Seq("--build", binBuildDir.toString, "--target", SYSCALL_TEST_BINARY),
        Map.empty
      )
    soct.log.debug(s"CMake build stdout (Test Binary):\n$binBuildStdout")
    soct.log.debug(s"CMake build stderr (Test Binary):\n$binBuildStderr")

    val testElf = paths.elfsDir.resolve(s"$SYSCALL_TEST_BINARY.elf")
    withClue(s"Expected test ELF `$testElf` to exist after building. ") {
      assert(testElf.toFile.exists())
    }

    // The syscall test writes and reads files; give it a dedicated scratch directory
    val tgtDir = simBuildDir.resolve("syscall-test-tgt")
    tgtDir.toFile.mkdirs()

    soct.log.info(s"Running simulator at `${simBinary}` with test ELF `${testElf}` and tgt dir `${tgtDir}`...")

    // Run the simulator; --tgt=<dir> is forwarded to the ELF as argv[1]
    val simProcess = new ProcessBuilder(simBinary.toString, testElf.toString, s"--tgt=$tgtDir", "--log-level=debug")
      .directory(simBuildDir.toFile)
      .redirectErrorStream(true) // Merge stdout and stderr
      .start()

    val simOutputBuilder = new StringBuilder
    val outputDrainer = new Thread(() => {
      val src = scala.io.Source.fromInputStream(simProcess.getInputStream)
      try {
        src.getLines().foreach { line =>
          simOutputBuilder.append(line).append("\n")
        }
      } finally {
        src.close()
      }
    })
    outputDrainer.setDaemon(true)
    outputDrainer.start()

    // The syscall test blocks on fgets(stdin). Feed it one line then close stdin so
    // it gets EOF — done in a separate thread to avoid deadlocking against the stdout read below.
    val stdinFeeder = new Thread(() => {
      val writer = new java.io.PrintWriter(simProcess.getOutputStream)
      writer.println("Hello from SoCeteer!\n")
      writer.flush()
      writer.close()
    })
    stdinFeeder.setDaemon(true)
    stdinFeeder.start()

    val simExitCode = simProcess.waitFor()
    stdinFeeder.join()
    outputDrainer.join()

    withClue(
      s"""Expected simulator to exit with code 0.
         |Simulator stdout/stderr:
         |${simOutputBuilder.toString()}
         |""".stripMargin
    ) {
      simExitCode shouldBe 0
    }
  }


  //***********
  // Fast TEST
  //***********
  val fastTests: Seq[Test] = Seq(
    Test(classOf[soct.RocketB2], XLEN_ALL)
  )

  "Fast test" should "run without errors" in {
    for {
      test <- fastTests
      xlen <- test.xlens
    } {
      withClue(s"Running test `${SOCTUtils.configName(test.config, xlen)}`. ") {
        runTest(test.config, xlen)
      }
    }
  }


  //***********
  // FULL TEST
  //***********
  "Full test" should "run without errors" in {
    val checkpointFile = testWorkspace.resolve("full-test-checkpoint.txt")
    val lastCheckpoint = if (checkpointFile.toFile.exists()) {
      val f = scala.io.Source.fromFile(checkpointFile.toFile)
      f.getLines().mkString.trim
    } else {
      ""
    }

    var checkpointIndex = 0
    val allTestsFlat = for {
      test <- allTests
      xlen <- test.xlens
    } yield (test, xlen)

    // Find starting index if resuming from checkpoint
    if (lastCheckpoint.nonEmpty) {
      checkpointIndex = allTestsFlat.indexWhere { case (test, xlen) =>
        SOCTUtils.configName(test.config, xlen) == lastCheckpoint
      }
      if (checkpointIndex >= 0) {
        soct.log.info(s"Resuming from checkpoint: $lastCheckpoint")
        checkpointIndex += 1 // Start from next test after checkpoint
      } else {
        checkpointIndex = 0
      }
    }

    try {
      for (i <- checkpointIndex until allTestsFlat.length) {
        val (test, xlen) = allTestsFlat(i)
        val testName = SOCTUtils.configName(test.config, xlen)

        try {
          withClue(s"Running test `$testName`. ") {
            runTest(test.config, xlen)
          }
          // Write successful checkpoint
          val writer = new java.io.PrintWriter(checkpointFile.toFile)
          writer.print(testName)
          writer.close()
          soct.log.info(s"Checkpoint saved: $testName")
        } catch {
          case e: Exception =>
            soct.log.error(s"Test failed at: $testName")
            soct.log.error(s"Last successful checkpoint: ${if (i > 0) allTestsFlat(i - 1)._2 else "none"}")
            soct.log.error(s"Checkpoint file: ${checkpointFile}")
            throw e
        }
      }
      // Clear checkpoint on full success
      checkpointFile.toFile.delete()
      soct.log.info("All tests passed. Checkpoint cleared.")
    } catch {
      case e: Exception =>
        soct.log.error(s"Test suite failed. Resume with: sbt test")
        throw e
    }
  }


  //***********
  // BOOM3 TEST
  //***********
  "Boom3 test" should "run without errors" in {
    for {
      test <- boom3Tests
      xlen <- test.xlens
    } {
      withClue(s"Running test `${SOCTUtils.configName(test.config, xlen)}`. ") {
        runTest(test.config, xlen)
      }
    }
  }
}