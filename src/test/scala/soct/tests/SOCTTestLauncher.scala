package soct.tests

import org.chipsalliance.cde.config.Config
import org.scalatest.flatspec.AnyFlatSpec

class SOCTTestLauncher extends AnyFlatSpec {
  val ELF_NAME_64 = "boot-sim.elf"
  val ELF_NAME_32 = "boot-sim-32.elf"

  val XLEN_32 = Seq(32)
  val XLEN_64 = Seq(64)
  val XLEN_ALL = Seq(32, 64)

  case class Test(
      config: Class[_ <: Config],
      xlens: Seq[Int],
      nCores: Int,
  )

  val boom3Tests: Seq[Test] = Seq(
    Test(classOf[soct.SmallBoomV3], XLEN_ALL, 1),
    Test(classOf[soct.DualSmallBoomV3], XLEN_ALL, 2),
    Test(classOf[soct.MediumBoomV3], XLEN_ALL, 1),
    Test(classOf[soct.DualMediumBoomV3], XLEN_ALL, 2),
    Test(classOf[soct.LargeBoomV3], XLEN_ALL, 1),
    Test(classOf[soct.DualLargeBoomV3], XLEN_ALL, 2),
    Test(classOf[soct.MegaBoomV3], XLEN_ALL, 1),
    Test(classOf[soct.DualMegaBoomV3], XLEN_ALL, 2)
  )

  val boom4Tests: Seq[Test] = Seq(
    Test(classOf[soct.SmallBoomV4], XLEN_ALL, 1),
    Test(classOf[soct.DualSmallBoomV4], XLEN_ALL, 2),
    Test(classOf[soct.MediumBoomV4], XLEN_ALL, 1),
    Test(classOf[soct.DualMediumBoomV4], XLEN_ALL, 2),
    Test(classOf[soct.LargeBoomV4], XLEN_ALL, 1),
    Test(classOf[soct.DualLargeBoomV4], XLEN_ALL, 2),
    Test(classOf[soct.MegaBoomV4], XLEN_ALL, 1),
    Test(classOf[soct.DualMegaBoomV4], XLEN_ALL, 2)
  )

  val rocketTests: Seq[Test] = Seq(
    Test(classOf[soct.RocketS1], XLEN_ALL, 1),
    Test(classOf[soct.RocketS2], XLEN_ALL, 2),
    Test(classOf[soct.RocketM1], XLEN_ALL, 1),
    Test(classOf[soct.RocketM2], XLEN_ALL, 2),
    Test(classOf[soct.RocketB1], XLEN_ALL, 1),
    Test(classOf[soct.RocketB2], XLEN_ALL, 2),
    Test(classOf[soct.RocketH1], XLEN_ALL, 1),
    Test(classOf[soct.RocketH2], XLEN_ALL, 2)
  )

  val gemminiTests: Seq[Test] = Seq(
    Test(classOf[soct.RocketB1Gem4Fp], XLEN_64, 1),
    Test(classOf[soct.RocketB1Gem4], XLEN_64, 1)
  )


  val allTests: Seq[Test] = boom3Tests ++ boom4Tests ++ rocketTests ++ gemminiTests


}
