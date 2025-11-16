package soct

import freechips.rocketchip.rocket.{WithNBigCores, WithNBreakpoints}
import freechips.rocketchip.subsystem.{WithEdgeDataBits, WithInclusiveCache}
import org.chipsalliance.cde.config.Config


class RocketB1Gem4Fp extends Config(
  new WithGemminiFp(4, 64).orElse(
    new WithInclusiveCache).orElse(
    new WithNBigCores(1)))

class RocketB1Gem4 extends Config(
  new WithGemmini(4, 64).orElse(
    new RocketB1()).orElse(
    new WithInclusiveCache()))
