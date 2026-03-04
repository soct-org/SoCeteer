package soct

import freechips.rocketchip.rocket.{WithNBigCores, WithNBreakpoints}
import freechips.rocketchip.subsystem.{SystemBusKey, WithEdgeDataBits, WithInclusiveCache}
import freechips.rocketchip.tile.BuildRoCC
import gemmini.{Gemmini, GemminiConfigs, GemminiFPConfigs}
import org.chipsalliance.cde.config.{Config, Parameters}
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.lazymodule.LazyModule


class RocketB1Gem4Fp extends Config(
  new WithGemminiFp(4, 64).orElse(
    new WithInclusiveCache).orElse(
    new WithNBigCores(1)))

class RocketB1Gem4 extends Config(
  new WithGemmini(4, 64).orElse(
    new RocketB1()).orElse(
    new WithInclusiveCache()))


class WithGemmini(mesh_size: Int, bus_bits: Int) extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      implicit val q = p
      implicit val v = implicitly[ValName]
      val config = GemminiConfigs.defaultConfig.copy(meshRows = mesh_size, meshColumns = mesh_size, dma_buswidth = bus_bits)
      //SOCTUtils.copyGemminiSoftware(config.generateHeader())
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
      //SOCTUtils.copyGemminiSoftware(config.generateHeader())
      LazyModule(new Gemmini(config))
    }
  )
  case SystemBusKey => up(SystemBusKey).copy(beatBytes = bus_bits / 8)
})