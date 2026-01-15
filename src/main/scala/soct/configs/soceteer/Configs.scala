package soct

import chisel3.util.log2Up
import freechips.rocketchip.devices.debug.{DebugModuleKey, DefaultDebugModuleParams}
import freechips.rocketchip.devices.tilelink.{BootROMLocated, BootROMParams, BuiltInErrorDeviceParams, CLINTKey, CLINTParams, DevNullParams, PLICKey, PLICParams}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tile.{LookupByHartId, MaxHartIdBits, PriorityMuxHartIdFromSeq}
import org.chipsalliance.cde.config.{Config, Field}
import soct.SOCTLauncher.SOCTConfig
import soct.xilinx.BDBuilder
import soct.xilinx.fpga.FPGA

import java.util.concurrent.atomic.AtomicBoolean

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
    new WithSingleBusClockSpeed(100.0) ++ // 100 MHz clock is the default
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

class RocketVivadoBaseConfig extends Config(
  new WithDTS("freechips,rocketchip-vivado", Nil) ++
    new WithBdBuilder(new BDBuilder) ++
    new WithDefaultSlavePort ++
    new WithJtagDTM ++
    new WithDebugSBA ++
    new WithDDR4ExtMem ++
    new WithSDCardPMOD ++
    new WithPeripheryClockDomain(100.0) ++ // 100 MHz periphery clock
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
 * Field to indicate whether the design should include DDR4 external memory on a specified port (index).
 */
case object HasDDR4ExtMem extends Field[Option[Int]](None)


class WithDDR4ExtMem(ddr4Idx: Int = 0) extends Config((_, _, _) => {
  case HasDDR4ExtMem => Some(ddr4Idx)
}
)

/*----------------- Storage ---------------*/

/**
 * Field to indicate whether the design should include an SDCard PMOD interface on a specified port (index).
 */
case object HasSDCardPMOD extends Field[Option[Int]](None)

class WithSDCardPMOD(pmodIdx: Int = 0) extends Config((_, _, _) => {
  case HasSDCardPMOD => Some(pmodIdx)
}
)


/*----------------- Clock Speeds ---------------*/

/**
 * Field to specify the periphery clock frequency in MHz - for parts like the SDCard controller and UART.
 * Default is 100 MHz.
 */
case object PeripheryClockFrequency extends Field[Double](100.0)

/**
 * Field to specify the system bus clock frequency in MHz.
 */
class WithPeripheryClockDomain(freqMHz: Double) extends Config((site, here, up) => {
  case PeripheryClockFrequency => freqMHz
})


class WithSingleBusClockSpeed(freqMHz: Double) extends Config(
  new WithPeripheryBusFrequency(freqMHz) ++
    new WithSystemBusFrequency(freqMHz) ++
    new WithMemoryBusFrequency(freqMHz) ++
    new WithFrontBusFrequency(freqMHz) ++
    new WithControlBusFrequency(freqMHz)
)

class WithHartBootFreqMHz(freqsMHz: Seq[Double]) extends Config((site, here, up) => {
  case TilesLocated(loc) if loc == InSubsystem =>
    val prev = up(TilesLocated(loc))

    val bootFreqsMHz = if (freqsMHz.length == 1 && prev.length > 1) {
      if (!WithHartBootFreqMHz.printed.getAndSet(true)) {
        log.info(s"Broadcasting single boot frequency ${freqsMHz.head} MHz to all ${prev.length} tiles")
      }
      Seq.fill(prev.length)(freqsMHz.head)
    } else if (freqsMHz.length == prev.length) {
      freqsMHz
    } else {
      throw new Exception(s"WithHartBootFreqMHz: number of frequencies (${freqsMHz.length}) does not match number of tiles (${prev.length})")
    }

    prev.zip(bootFreqsMHz).map { case (tap: RocketTileAttachParams, mhz) =>
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

object WithHartBootFreqMHz {
  private[soct] val printed = new AtomicBoolean(false)
}

/*----------------- FPGA ---------------*/

/**
 * Field to indicate whether the design runs on a Xilinx FPGA.
 */
case object HasXilinxFPGA extends Field[Option[FPGA]](None)


/**
 * Field to hold the BDBuilder instance for Xilinx FPGA designs.
 */
case object HasBdBuilder extends Field[Option[BDBuilder]](None)


/**
 * Class to add a BDBuilder to the configuration. Used to generate Vivado block designs.
 * @param bd The BDBuilder instance to add to the configuration.
 */
class WithBdBuilder(bd: BDBuilder) extends Config((_, _, _) => {
  case HasBdBuilder => Some(bd)
})

class WithXilinxFPGA(fpga: FPGA) extends Config((_, _, _) => {
  case HasXilinxFPGA => Some(fpga)
})


/*----------------- Reset Schemes ---------------*/
class WithResetScheme(scheme: SubsystemResetScheme) extends Config((site, here, up) => {
  case SubsystemResetSchemeKey => scheme
})