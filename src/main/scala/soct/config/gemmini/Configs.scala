package soct

import freechips.rocketchip.rocket.WithNBigCores
import freechips.rocketchip.subsystem.{SystemBusKey, WithInclusiveCache}
import freechips.rocketchip.tile.BuildRoCC
import gemmini.{Gemmini, GemminiConfigs, GemminiFPConfigs}
import org.chipsalliance.cde.config.{Config, Parameters}
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import soct.build.BuildInfo.gemminiDir

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardCopyOption}


class RocketB1Gem4Fp extends Config(
  new WithGemminiFp(4, 64).orElse(
    new WithInclusiveCache).orElse(
    new WithNBigCores(1)))


class RocketB1Gem4 extends Config(
  new WithGemmini(4, 64).orElse(
    new RocketB1()).orElse(
    new WithInclusiveCache()))


object GemminiUtils {

  def copyRoCCTests(header: String)(implicit p: Parameters): Unit = {
    val soctPath = p(HasSOCTPaths)
    val srcDir = Paths.get(gemminiDir).resolve("software").resolve("gemmini-rocc-tests")
    val srcInclude = srcDir.resolve("include")

    val destDir = soctPath.systemDir.resolve("gemmini-rocc-tests")
    val destInclude = destDir.resolve("include")
    // Create include directory if it doesn't exist
    Files.createDirectories(destInclude)

    // Recursively copy all files from roccInclude to destInclude
    Files.walk(srcInclude).forEach { srcPath =>
      val destPath = destInclude.resolve(srcInclude.relativize(srcPath))
      if (Files.isDirectory(srcPath)) {
        Files.createDirectories(destPath)
      } else {
        Files.copy(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING)
      }
    }

    val destParams = destInclude.resolve("gemmini_params.h")
    Files.write(destParams, header.getBytes(StandardCharsets.UTF_8))

    // Gemmini includes rocc-software/scr/xcustom.h so we copy that as well
    val srcXCustom = srcDir.resolve("rocc-software").resolve("src").resolve("xcustom.h")
    val destXCustom = destDir.resolve("rocc-software").resolve("src").resolve("xcustom.h")
    Files.createDirectories(destXCustom.getParent)
    Files.copy(srcXCustom, destXCustom, StandardCopyOption.REPLACE_EXISTING)
  }
}


class WithGemmini(mesh_size: Int, bus_bits: Int) extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      implicit val q = p
      implicit val v = implicitly[ValName]
      val config = GemminiConfigs.defaultConfig.copy(meshRows = mesh_size, meshColumns = mesh_size, dma_buswidth = bus_bits)
      GemminiUtils.copyRoCCTests(config.generateHeader())
      LazyModule(new Gemmini(config))
    }
  )
  case SystemBusKey => up(SystemBusKey).copy(beatBytes = bus_bits / 8)
})

class WithGemminiFp(mesh_size: Int, bus_bits: Int) extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      implicit val q = p
      implicit val v = implicitly[ValName]
      val config = GemminiFPConfigs.FP32DefaultConfig.copy(meshRows = mesh_size, meshColumns = mesh_size, dma_buswidth = bus_bits)
      GemminiUtils.copyRoCCTests(config.generateHeader())
      LazyModule(new Gemmini(config))
    }
  )
  case SystemBusKey => up(SystemBusKey).copy(beatBytes = bus_bits / 8)
})