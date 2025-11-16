package soct

import freechips.rocketchip.subsystem._
import freechips.rocketchip.system.BaseConfig
import org.chipsalliance.cde.config.Config

/*----------------- Base Configs ---------------*/

class RocketSimBaseConfig extends Config(
    new WithExtMemSize(0x40000000) ++
    new WithNExtTopInterrupts(8) ++
    new WithEdgeDataBits(64) ++
    new WithCoherentBusTopology ++
    new WithoutTLMonitors ++
    new WithNoSlavePort ++
    new WithClockGateModel ++
    new BaseConfig
)

class RocketBaseConfig extends Config(
    new WithNExtTopInterrupts(8) ++
    new WithDTS("freechips,rocketchip-vivado", Nil) ++
    new WithJtagDTM() ++
    new WithDebugSBA ++
    new WithEdgeDataBits(64) ++
    new WithCoherentBusTopology ++
    new WithoutTLMonitors ++
    new BaseConfig)


class ExtMem64Bit extends Config(new WithExtMemSize(0x380000000L))

class ExtMem32Bit extends Config(new WithExtMemSize(0x80000000L))