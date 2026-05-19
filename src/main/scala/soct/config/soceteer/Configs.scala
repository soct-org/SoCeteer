package soct

import chisel3.util.log2Up
import freechips.rocketchip.devices.debug.{DebugModuleKey, DefaultDebugModuleParams}
import freechips.rocketchip.devices.tilelink.{BootROMLocated, BootROMParams, BuiltInErrorDeviceParams, CLINTKey, CLINTParams, DevNullParams, PLICKey, PLICParams}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tile.{LookupByHartId, MaxHartIdBits, PriorityMuxHartIdFromSeq}
import org.chipsalliance.cde.config.{Config, Field}
import soct.SOCTLauncher.SOCTConfig
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.fpga.FPGA

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
    new WithBdBuilder(new SOCTBdBuilder) ++
    new WithDefaultSlavePort ++
    new WithJtagDTM ++
    new WithDebugSBA ++
    new WithSDCardPMOD ++
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

class WithExtMemCapacity(cap: BigInt) extends Config((_, _, up) => {
  case ExtMem => up(ExtMem).map(x => x.copy(master = x.master.copy(size = cap - x.master.base)))
})

/*----------------- Storage ---------------*/

/**
 * Field to indicate whether the design should include an SDCard PMOD interface on a specified port (index).
 */
case object HasSDCardPMOD extends Field[Option[Int]](None)

case object NeedsFatFS extends Field[Boolean](false)

class WithSDCardPMOD(pmodIdx: Int = 0) extends Config((_, _, _) => {
  case HasSDCardPMOD => Some(pmodIdx)
  case NeedsFatFS => true
}
)


/*----------------- Clock Speeds ---------------*/

/**
 * Field to specify the periphery clock domain frequency in Mhz - for parts like the SDCard controller and UART.
 */
case object PeripheryClockDomain extends Field[Double](100.0)

/**
 * Field to specify the system bus clock frequency in MHz.
 */
class WithPeripheryClockSpeed(freqMHz: Double) extends Config((site, here, up) => {
  case PeripheryClockDomain => freqMHz
})

/**
 * Class to set all clock domains to the same frequency. This is a common case for simpler designs, and is also useful for testing and simulation.
 *
 * @param freqMHz the frequency in MHz to set for all clock domains
 */
class WithSingleBusClockSpeed(freqMHz: Double) extends Config(
  new WithPeripheryBusFrequency(freqMHz) ++
    new WithSystemBusFrequency(freqMHz) ++
    new WithMemoryBusFrequency(freqMHz) ++
    new WithFrontBusFrequency(freqMHz) ++
    new WithControlBusFrequency(freqMHz)
)

/*----------------- FPGA ---------------*/

/**
 * Field to indicate whether the design runs on a Xilinx FPGA.
 */
case object XilinxFPGAKey extends Field[Option[FPGA]](None)


/**
 * Field to hold the BDBuilder instance for Xilinx FPGA designs.
 */
case object BdBuilderKey extends Field[Option[SOCTBdBuilder]](None)


/**
 * Class to add a BDBuilder to the configuration. Used to generate Vivado block designs.
 *
 * @param bd The BDBuilder instance to add to the configuration.
 */
class WithBdBuilder(bd: SOCTBdBuilder) extends Config((_, _, _) => {
  case BdBuilderKey => Some(bd)
})

/**
 * Class to specify the Xilinx FPGA board for the design.
 *
 * @param fpga The FPGA board object.
 */
class WithXilinxFPGA(fpga: FPGA) extends Config((_, _, _) => {
  case XilinxFPGAKey => Some(fpga)
})


/*----------------- Reset Schemes ---------------*/


/**
 * Class to specify the reset scheme for the design.
 *
 * @param scheme The reset scheme to use.
 */
class WithResetScheme(scheme: SubsystemResetScheme) extends Config((site, here, up) => {
  case SubsystemResetSchemeKey => scheme
})

