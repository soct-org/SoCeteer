package soct

import chisel3.util.log2Up
import freechips.rocketchip.devices.debug.{DebugModuleKey, DefaultDebugModuleParams}
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tile.MaxHartIdBits
import org.chipsalliance.cde.config.{Config, Field}
import soct.SOCTFreq._
import soct.SOCTLauncher.SOCTConfig
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.fpga.{DDR4PortParams, FPGA}

import scala.annotation.unused

/*----------------- Base Configs ---------------*/

/** Foundation of every SOCT design: bus layout, boot ROM, debug module, CLINT and PLIC. */
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


/** Common Rocket setup shared by the simulation and Vivado base configs. */
class RocketBaseConfig extends Config(
  new WithTimebase(BigInt(1000000)) ++ // 1 MHz timebase
    new WithEdgeDataBits(64) ++
    new WithDefaultMemPort ++
    new WithDefaultMMIOPort ++
    new WithoutTLMonitors ++
    new WithCoherentBusTopology ++
    new BaseSubsystemConfig
)

/** Base config of the Verilator simulation target. */
class RocketSimBaseConfig extends Config(
  new WithNExtTopInterrupts(8) ++ // We don't know how many interrupts are needed, so we just use 8
    new WithDTS("freechips,rocketchip-unknown", Nil) ++
    new WithNoSlavePort ++
    new WithClockGateModel ++
    new WithResetScheme(ResetSynchronous) ++ // Only io_clocks are top-level resets
    new RocketBaseConfig
)

/** Base config of the Vivado FPGA target: block-design builder, JTAG debug, SD card and UART. */
class RocketVivadoBaseConfig extends Config(
  new WithNExtTopInterrupts(1) ++
    new WithDTS("freechips,rocketchip-vivado", Nil) ++
    new WithBdBuilder(new SOCTBdBuilder) ++
    new WithDefaultSlavePort ++
    new WithJtagDTM ++
    new WithDebugSBA ++
    new WithSDCardPMOD ++
    new WithUART ++
    new WithResetScheme(ResetSynchronousFull) ++ // io_clocks and several other resets are top-level resets
    new RocketBaseConfig
)

/*----------------- SoCeteer ---------------*/

/** Field holding the resolved output paths of the current run. */
case object HasSOCTPaths extends Field[SOCTPaths]

class WithSOCTPaths(paths: SOCTPaths) extends Config((_, _, _) => {
  case HasSOCTPaths => paths
}
)

/** Field holding the launcher's resolved SOCT configuration. */
case object HasSOCTConfig extends Field[SOCTConfig]

class WithSOCTConfig(config: SOCTConfig) extends Config((_, _, _) => {
  case HasSOCTConfig => config
}
)

/**
 * Field for a faster place-and-route mode that would trade away convenience hardware.
 * Currently UNUSED: nothing in the generator consumes it (its former use - dropping the
 * debugger-reset wiring - was removed because the savings were negligible). Kept, with
 * the `--fast-pnr` launcher flag, as a hook for future PnR-effort tradeoffs.
 */
case object FastPnR extends Field[Boolean](false)

@unused // reserved: no generator code consumes FastPnR at present (see the field's doc)
class WithFastPnR extends Config((_, _, _) => {
  case FastPnR => true
})


/*----------------- Memory ---------------*/

/** Field holding the DDR4 memory ports (with resolved parts/capacities) of the design. */
case object RegisteredMems extends Field[Seq[DDR4PortParams]](Nil)


/** Registers several memory channels and sizes ExtMem to their summed capacity. */
class WithMultiMemLayout(mems: Seq[DDR4PortParams]) extends Config((_, _, up) => {
  case ExtMem => up(ExtMem).map(x => x.copy(
    nMemoryChannels = mems.length,
    master = x.master.copy(size = mems.map(_.getCap).sum.value)))
  case RegisteredMems => mems
})

/** Registers a single memory channel and sizes ExtMem to its capacity. */
class WithSingleMemLayout(mem: DDR4PortParams) extends Config((_, _, up) => {
  case ExtMem => up(ExtMem).map(x => x.copy(
    nMemoryChannels = 1,
    master = x.master.copy(size = mem.getCap.value)))
  case RegisteredMems => Seq(mem)
})


/*----------------- MMIO ---------------*/

/**
 * Field to indicate whether the design should include an SDCard PMOD interface on a specified port (index).
 */
case object HasSDCardPMOD extends Field[Option[Int]](None)

case object NeedsFatFS extends Field[Boolean](false)

class WithSDCardPMOD(pmodIdx: Int = 0) extends Config((site, here, up) => {
  case HasSDCardPMOD => Some(pmodIdx)
  case NeedsFatFS => true
}
)

/**
 * Field collecting the name suffixes contributed by [[SOCTFeatureConfig]] fragments.
 * The launcher appends them to the system name, so feature designs get their own
 * workspace directories.
 */
case object SOCTNameSuffixes extends Field[Seq[String]](Nil)

/**
 * Base class for config fragments that change the design substantially enough that its
 * outputs must not share a workspace with the plain config: the suffix becomes part of
 * the generated system name (`<config>-<xlen>-<suffix>[-<suffix>...]`), so e.g. a design
 * with [[WithVideoStream]] lands in `workspace/RocketB1-64-video/` instead of silently
 * overwriting `workspace/RocketB1-64/`. Suffixes accumulate in composition order
 * (base-most fragment first) and are deduplicated.
 *
 * @param suffix the fixed name suffix this feature contributes (e.g. "video")
 * @param impl   the fragment's actual configuration
 */
abstract class SOCTFeatureConfig(suffix: String, impl: Config) extends Config(
  new Config((_, _, up) => {
    case SOCTNameSuffixes => up(SOCTNameSuffixes) :+ suffix
  }) ++ impl
)

/**
 * Parameters of the DisplayPort video stream (PL framebuffer -> PS DP live video).
 * The default is 720p60: 1080p60 needs more frame-fetch bandwidth than the coherent DMA
 * path sustains at 100 MHz (the design generation validates this and fails loudly).
 *
 * @param width      active pixels per line
 * @param height     active lines per frame
 * @param fps        frames per second
 * @param incoherent whether the frame-fetch DMA bypasses the SoC's coherent fabric and reads
 *                   DRAM through its own memory-controller port (see [[WithIncoherentVideoStream]])
 */
case class VideoStreamParams(width: Int = 1280, height: Int = 720, fps: Int = 60,
                             incoherent: Boolean = false)

/**
 * Field enabling the DisplayPort video pipeline: an AXI VDMA reads frames from DRAM and
 * streams them into the PS DP controller's live video input. None disables the pipeline.
 */
case object HasVideoStream extends Field[Option[VideoStreamParams]](None)

/**
 * Adds the DisplayPort video pipeline (see [[HasVideoStream]]) with the default 720p60 timing.
 * Select via `--with-config soct.WithVideoStream`; the design's outputs land in a
 * `-video`-suffixed workspace (see [[SOCTFeatureConfig]]).
 */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class WithVideoStream() extends SOCTFeatureConfig("video", new Config((site, here, up) => {
  case HasVideoStream => Some(VideoStreamParams())
}))

/**
 * The video pipeline with an INCOHERENT frame fetch: the VDMA masters the memory-side
 * SmartConnect directly (`VDMA -> mem_smc -> DDR4`) instead of reaching DRAM through the
 * coherent fabric (`dma_smc -> l2_frontend_bus -> fbus -> sbus -> coherence manager -> mbus`).
 *
 * Why: the coherent DMA port is a control-plane path - its AXI-to-TileLink conversion is
 * FIFO-ordered and caps transactions in flight, so its throughput is latency-bound rather than
 * bandwidth-bound. Measured on the ZCU104 with an L2 in that path, the frame fetch
 * fell from 165 MB/s to 82 MB/s under only ~50 MB/s of concurrent CPU traffic - below the
 * 166 MB/s a 720p60 stream needs - so the video out lost lock and the display went black. On the
 * private port the fetch meets other masters only at the memory controller, where the combined
 * demand is a few percent of capacity: a structural guarantee instead of a statistical one. It
 * also stops a streaming master from continuously evicting the cores' working sets from the L2.
 *
 * The cost is that DRAM is no longer coherent with the CPU's caches for the framebuffer:
 * software must make freshly rendered pixels visible before the DMA reads them - via the L2's
 * `Flush64` control register when an L2 is present ([[WithL2Cache]], which flushes the L1 too
 * because the cache is inclusive), or by evicting the L1 otherwise. The generated device tree
 * marks the pipeline `soct,incoherent` so software can tell which contract applies.
 *
 * Select via `--with-config soct.WithIncoherentVideoStream`; outputs land in a `-video-nc`
 * suffixed workspace, so coherent and incoherent designs never overwrite each other. Note that
 * without an L2 the coherent pipeline ([[WithVideoStream]]) already sustains 720p60 under load -
 * this fragment earns its keep when combining video WITH an L2, or at timings whose bandwidth
 * the coherent path cannot guarantee.
 */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class WithIncoherentVideoStream() extends SOCTFeatureConfig("video-nc", new Config((site, here, up) => {
  case HasVideoStream => Some(VideoStreamParams(incoherent = true))
}))

/**
 * Adds a banked inclusive L2 (SiFive InclusiveCache) as the coherence manager in place of the
 * default broadcast hub, which has no data array: without it every L1 miss pays the full DDR4
 * round trip (SmartConnect CDC + DDRMC, 100+ core cycles), so an L2 hit is a large latency win
 * for CPU-bound work on FPGA.
 *
 * Select via `--with-config soct.WithL2Cache`; the design's outputs land in an `-l2`-suffixed
 * workspace (see [[SOCTFeatureConfig]]), so L2 and non-L2 builds never overwrite each other.
 * The fragment must compose above [[RocketBaseConfig]]'s `WithCoherentBusTopology`, whose
 * `SubsystemBankedCoherenceKey` the InclusiveCache reads - which `--with-config` guarantees.
 * Simulation gains nothing from it: the Verilator target has a single coherent master (no DMA)
 * and models memory as a fast in-RTL SRAM, so the L2 neither exercises its coherence role nor
 * hides any latency there.
 */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class WithL2Cache() extends SOCTFeatureConfig("l2", new WithInclusiveCache())


case object HasUART extends Field[Boolean](false)

class WithUART extends Config((site, here, up) => {
  case HasUART => true
})


/*----------------- Clock Speeds ---------------*/

/**
 * Field to specify the periphery clock domain frequency - for parts like the SDCard controller and UART.
 */
case object PeripheryClockDomain extends Field[Freq](100.MHz)

/**
 * Class to set the periphery clock domain frequency.
 *
 * @param freq the periphery clock frequency
 */
class WithPeripheryClockSpeed(freq: Freq) extends Config((site, here, up) => {
  case PeripheryClockDomain => freq
})

/**
 * Class to set all clock domains to the same frequency. This is a common case for simpler designs, and is also useful for testing and simulation.
 * (RocketChip's With*BusFrequency fragments take the value in MHz.)
 *
 * @param freq the frequency to set for all clock domains
 */
class WithSingleBusClockSpeed(freq: Freq) extends Config(
  new WithPeripheryBusFrequency(freq.toMHz) ++
    new WithSystemBusFrequency(freq.toMHz) ++
    new WithMemoryBusFrequency(freq.toMHz) ++
    new WithFrontBusFrequency(freq.toMHz) ++
    new WithControlBusFrequency(freq.toMHz)
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
