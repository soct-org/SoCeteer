package soct.tests

import org.chipsalliance.cde.config.Config
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import soct.SOCTNames.{DEFAULT_EXAMPLE_BINARY, SOCT_SIMULATOR_EXE, SOCT_SYSTEM_CMAKE_KEY}
import soct.{SOCTLauncher, SOCTPaths, SOCTPathsBase, SOCTUtils}

import java.nio.file.Files

class SimulationSpec extends AnyFlatSpec {

  val XLEN_32 = Seq(32)
  val XLEN_64 = Seq(64)
  val XLEN_ALL = Seq(32, 64)

  val testWorkspace = SOCTPaths.get("test-workspace")

  case class Test(
                   config: Class[_ <: Config],
                   xlens: Seq[Int]
                 )

  val boom3Tests: Seq[Test] = Seq(
    Test(classOf[soct.SmallBoomV3], XLEN_ALL),
    Test(classOf[soct.DualSmallBoomV3], XLEN_ALL),
    Test(classOf[soct.MediumBoomV3], XLEN_ALL),
    Test(classOf[soct.DualMediumBoomV3], XLEN_ALL),
    Test(classOf[soct.LargeBoomV3], XLEN_ALL),
    Test(classOf[soct.DualLargeBoomV3], XLEN_ALL),
    Test(classOf[soct.MegaBoomV3], XLEN_ALL),
    Test(classOf[soct.DualMegaBoomV3], XLEN_ALL)
  )

  val boom4Tests: Seq[Test] = Seq(
    Test(classOf[soct.SmallBoomV4], XLEN_ALL),
    Test(classOf[soct.DualSmallBoomV4], XLEN_ALL),
    Test(classOf[soct.MediumBoomV4], XLEN_ALL),
    Test(classOf[soct.DualMediumBoomV4], XLEN_ALL),
    Test(classOf[soct.LargeBoomV4], XLEN_ALL),
    Test(classOf[soct.DualLargeBoomV4], XLEN_ALL),
    Test(classOf[soct.MegaBoomV4], XLEN_ALL),
    Test(classOf[soct.DualMegaBoomV4], XLEN_ALL)
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


  val allTests: Seq[Test] = boom3Tests ++ boom4Tests ++ rocketTests ++ gemminiTests

  //***********
  // QUICK TEST
  //***********
  val defaultTest: Test = Test(classOf[soct.RocketB4], XLEN_64)
  "Fast test" should "run without errors" in {
    val xlen = defaultTest.xlens.head
    val outDir = testWorkspace.resolve(SOCTUtils.configName(defaultTest.config, xlen))
    val paths = SOCTPathsBase(outDir)
    val args = Seq(
      "--config", defaultTest.config.getCanonicalName,
      "--xlen", xlen.toString,
      "--out-dir", outDir.toString,
      "-t", "verilator",
      "--no-latest-soct-system" // Don't create symlink to latest SOCTSystem.cmake file for tests, to avoid conflicts between tests and user builds
    )
    // Catch stdout and stderr to prevent cluttering test output, but still print if the test fails
    SOCTLauncher.main(args.toArray)

    withClue(s"Expected `${paths.soctSystemCMakeFile}` to exist. ") {
      paths.soctSystemCMakeFile.toFile.exists() shouldBe true
    }

    val defs = Map(
      SOCT_SYSTEM_CMAKE_KEY -> paths.soctSystemCMakeFile.toString,
    )
    val simBuildDir = paths.buildDir.resolve("sim-build")
    simBuildDir.toFile.mkdirs()

    // Configure and build the simulator in the test build directory, using the generated SOCTSystem.cmake file
    val (simCfgStdout, simCfgStderr) = SOCTUtils.runCMakeCommand(
      Seq("-S", SOCTPaths.get("sim").toString, "-B", simBuildDir.toString, "-G", "Ninja"),
      defs ++ Map("VL_THREADS" -> "1"), // Disable verilator multithreading to avoid issues on GitHub Actions runners with limited resources
      streamOutput = true
    )
    soct.log.info(s"CMake configure stdout (Simulator):\n$simCfgStdout")
    soct.log.info(s"CMake configure stderr (Simulator):\n$simCfgStderr")

    val (simBuildStdout, simBuildStderr) =
      SOCTUtils.runCMakeCommand(Seq("--build", simBuildDir.toString), Map.empty, streamOutput = true)
    soct.log.info(s"CMake build stdout (Simulator):\n$simBuildStdout")
    soct.log.info(s"CMake build stderr (Simulator):\n$simBuildStderr")

    // Validate that the simulator binary was created:
    val simBinary = simBuildDir.resolve(SOCT_SIMULATOR_EXE)
    withClue(s"Expected simulator binary `${simBinary}` to exist after building. ") {
      simBinary.toFile.exists() shouldBe true
    }


    // Now configure and build the test binary using the same SOCTSystem.cmake file, but with a separate build directory
    val binBuildDir = paths.buildDir.resolve("prog-build")
    binBuildDir.toFile.mkdirs()

    val (binCfgStdout, binCfgStderr) = SOCTUtils.runCMakeCommand(
      Seq("-S", SOCTPaths.get("binaries").toString, "-B", binBuildDir.toString, "-G", "Ninja"),
      defs,
      streamOutput = true
    )
    soct.log.info(s"CMake configure stdout (Test Binary):\n$binCfgStdout")
    soct.log.info(s"CMake configure stderr (Test Binary):\n$binCfgStderr")

    val (binBuildStdout, binBuildStderr) =
      SOCTUtils.runCMakeCommand(
        Seq("--build", binBuildDir.toString, "--target", DEFAULT_EXAMPLE_BINARY),
        Map.empty,
        streamOutput = true
      )
    soct.log.info(s"CMake build stdout (Test Binary):\n$binBuildStdout")
    soct.log.info(s"CMake build stderr (Test Binary):\n$binBuildStderr")

    val testElf = paths.elfsDir.resolve(s"$DEFAULT_EXAMPLE_BINARY.elf")
    withClue(s"Expected test ELF `${testElf}` to exist after building. ") {
      testElf.toFile.exists() shouldBe true
    }

    soct.log.info(s"Running simulator at `${simBinary}` with test ELF `${testElf}`...")

    // Run the simulator with the test ELF in build directory as the working directory
    val simProcess = new ProcessBuilder(simBinary.toString, testElf.toString)
      .directory(simBuildDir.toFile)
      .redirectErrorStream(true) // Merge stdout and stderr
      .start()

    val simOutput = scala.io.Source.fromInputStream(simProcess.getInputStream).mkString
    val simExitCode = simProcess.waitFor()

    val simLogFile = simBuildDir.resolve("log.txt") // TODO change once simulator is updated to have a real argparser
    val simLog =
      if (simLogFile.toFile.exists()) Files.readString(simLogFile)
      else "<log.txt not found>"

    withClue(
      s"""Expected simulator to exit with code 0.
         |Simulator stdout/stderr:
         |$simOutput
         |
         |Simulator log.txt:
         |$simLog
         |""".stripMargin
    ) {
      simExitCode shouldBe 0
    }
  }
}
