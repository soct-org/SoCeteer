package soct

import freechips.rocketchip.rocket.{WithNBigCores, WithNBreakpoints, WithNHugeCores, WithNMedCores, WithNSmallCores}
import org.chipsalliance.cde.config.Config

/*----------------- Rocket Basic ---------------*/
class RocketS1 extends Config(
  new WithNSmallCores(1)
)

class RocketS2 extends Config(
  new WithNSmallCores(2)
)

class RocketM1 extends Config(
  new WithNMedCores(1)
)

class RocketM2 extends Config(
  new WithNMedCores(2)
)

class RocketB1 extends Config(
  new WithNBigCores(1)
)

class RocketB2 extends Config(
  new WithNBigCores(2)
)

class RocketH1 extends Config(
  new WithNHugeCores(1)
)

class RocketH2 extends Config(
  new WithNHugeCores(2)
)