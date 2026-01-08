package soct

import chisel3.util.log2Up
import freechips.rocketchip.devices.debug.{DebugModuleKey, DefaultDebugModuleParams}
import freechips.rocketchip.devices.tilelink.{BootROMLocated, BootROMParams, BuiltInErrorDeviceParams, CLINTKey, CLINTParams, DevNullParams, PLICKey, PLICParams}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tile.{LookupByHartId, MaxHartIdBits, PriorityMuxHartIdFromSeq}
import org.chipsalliance.cde.config.{Config, Field}
import soct.SOCTLauncher.SOCTConfig
import soct.xilinx.fpga.FPGA

/*----------------- Base Configs ---------------*/
class BaseSubsystemConfig extends Config((site, here, up) => {
  // Tile parameters
  case MaxXLen => (site(PossibleTileLocations).flatMap(loc => site(TilesLocated(loc)))
    .map(_.tileParams.core.xLen) :+ 32).max
  case MaxHartIdBits => log2Up((site(PossibleTileLocations).flatMap(loc => site(TilesLocated(loc)))
    .map(_.tileParams.tileId) :+ 0).max + 1)
  // Interconnect parameters
  case SystemBusKey => SystemBusParams(
    beatBytes = 8,
    blockBytes = site(CacheBlockBytes))
  case ControlBusKey => PeripheryBusParams(
    beatBytes = 8,
    blockBytes = site(CacheBlockBytes),
    errorDevice = Some(BuiltInErrorDeviceParams(
      errorParams = DevNullParams(List(AddressSet(0x3000, 0xfff)), maxAtomic = 8, maxTransfer = 4096))))
  case PeripheryBusKey => PeripheryBusParams(
    beatBytes = 8,
    blockBytes = site(CacheBlockBytes))
  case MemoryBusKey => MemoryBusParams(
    beatBytes = 8,
    blockBytes = site(CacheBlockBytes))
  case FrontBusKey => FrontBusParams(
    beatBytes = 8,
    blockBytes = site(CacheBlockBytes))
  // Additional device Parameters
  case BootROMLocated(InSubsystem) => Seq(BootROMParams())
  case HasTilesExternalResetVectorKey => false
  case DebugModuleKey => Some(DefaultDebugModuleParams(64))
  case CLINTKey => Some(CLINTParams())
  case PLICKey => Some(PLICParams())
  case TilesLocated(InSubsystem) => Nil
  case PossibleTileLocations => Seq(InSubsystem)
})


class RocketBaseConfig extends Config(
  new WithNExtTopInterrupts(8) ++
    new WithSingleClockDomain(100.0) ++ // 100 MHz clock is the default
    new WithTimebase(BigInt(1000000)) ++ // 1 MHz timebase
    new WithEdgeDataBits(64) ++
    new WithDefaultMemPort ++
    new WithDefaultMMIOPort ++
    new WithoutTLMonitors ++
    new WithCoherentBusTopology ++
    new BaseSubsystemConfig
)

class RocketSimBaseConfig extends Config(
  new WithDTS("freechips,rocketchip-unknown", Nil) ++
    new WithNoSlavePort ++
    new WithClockGateModel ++
    new WithResetScheme(ResetSynchronous) ++ // Only io_clocks are top-level resets
    new RocketBaseConfig
)

class RocketSynBaseConfig extends Config(
  new WithDTS("freechips,rocketchip-vivado", Nil) ++
    new WithDefaultSlavePort ++
    new WithJtagDTM ++
    new WithDebugSBA ++
    new WithDDR4ExtMem ++
    new WithSDCard ++
    new WithResetScheme(ResetSynchronousFull) ++ // io_clocks and several other resets are top-level resets
    new RocketBaseConfig
)

/*----------------- SoCeteer ---------------*/
case object HasSOCTPaths extends Field[SOCTPaths]

class WithSOCTPaths(paths: SOCTPaths) extends Config((_, _, _) => {
  case HasSOCTPaths => paths
}
)

case object HasSOCTConfig extends Field[SOCTConfig]

class WithSOCTConfig(config: SOCTConfig) extends Config((_, _, _) => {
  case HasSOCTConfig => config
}
)


/*----------------- Memory ---------------*/
class ExtMem64Bit extends Config(new WithExtMemSize(0x380000000L))

class ExtMem32Bit extends Config(new WithExtMemSize(0x80000000L))

/**
 * Enable DDR4 external memory interface
 */
case object HasDDR4ExtMem extends Field[Boolean](false)


class WithDDR4ExtMem extends Config((_, _, _) => {
  case HasDDR4ExtMem => true
}
)

/*----------------- Storage ---------------*/

case object HasSDCard extends Field[Boolean](false)

class WithSDCard extends Config((_, _, _) => {
  case HasSDCard => true
}
)



/*----------------- Clock Speeds ---------------*/
class WithSingleClockDomain(freqMHz: Double) extends Config(
  new WithPeripheryBusFrequency(freqMHz) ++
    new WithSystemBusFrequency(freqMHz) ++
    new WithMemoryBusFrequency(freqMHz) ++
    new WithFrontBusFrequency(freqMHz) ++
    new WithControlBusFrequency(freqMHz)
)

class WithHartBootFreqMHz(freqsMHz: Seq[Double]) extends Config((site, here, up) => {
  case TilesLocated(loc) if loc == InSubsystem =>
    val prev = up(TilesLocated(loc))
    require(freqsMHz.length >= prev.length, s"Need at least ${prev.length} boot frequencies, got ${freqsMHz.length}")
    prev.zip(freqsMHz).map { case (tap: RocketTileAttachParams, mhz) =>
      val tp = tap.tileParams
      tap.copy(tileParams = tp.copy(
        core = tp.core.copy(
          bootFreqHz = BigInt((mhz * 1e6).round)
        )
      ))
    }

  // Wire hartId -> TileParams lookup for any per-hart fields (including bootFreqHz)
  case LookupByHartId =>
    PriorityMuxHartIdFromSeq(site(TilesLocated(InSubsystem)).map(_.tileParams))
})

/*----------------- FPGA ---------------*/

/**
 * Field to indicate whether the design runs on a Xilinx FPGA.
 */
case object HasXilinxFPGA extends Field[Option[FPGA]](None)


class WithXilinxFPGA(fpga: FPGA) extends Config((_, _, _) => {
  case HasXilinxFPGA => Some(fpga)
})


/*----------------- Reset Schemes ---------------*/
class WithResetScheme(scheme: SubsystemResetScheme) extends Config((site, here, up) => {
  case SubsystemResetSchemeKey => scheme
})