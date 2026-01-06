package soct

import freechips.rocketchip.subsystem._
import freechips.rocketchip.system.BaseConfig
import org.chipsalliance.cde.config.{Config, Field}

/*----------------- Base Configs ---------------*/

class RocketSimBaseConfig extends Config(
  new WithExtMemSize(0x40000000) ++
    new WithNExtTopInterrupts(8) ++
    new WithEdgeDataBits(64) ++
    new WithCoherentBusTopology ++
    new WithoutTLMonitors ++
    new WithNoSlavePort ++
    new WithClockGateModel ++
    new WithResetScheme(ResetSynchronous) ++ // Only io_clocks are top-level resets
    new BaseConfig
)

class RocketSynBaseConfig extends Config(
  new WithNExtTopInterrupts(8) ++
    new WithDTS("freechips,rocketchip-vivado", Nil) ++
    new WithJtagDTM() ++
    new WithDebugSBA ++
    new WithEdgeDataBits(64) ++
    new WithCoherentBusTopology ++
    new WithoutTLMonitors ++
    new WithDDR4ExtMem ++
    new WithResetScheme(ResetSynchronousFull) ++ // io_clocks and several other resets are top-level resets
    new BaseConfig)


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


/*----------------- Reset Schemes ---------------*/
class WithResetScheme(scheme: SubsystemResetScheme) extends Config((site, here, up) => {
  case SubsystemResetSchemeKey => scheme
})