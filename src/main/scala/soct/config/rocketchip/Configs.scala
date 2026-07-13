package soct

import freechips.rocketchip.rocket.{WithNBigCores, WithNBreakpoints, WithNHugeCores, WithNMedCores, WithNSmallCores}
import org.chipsalliance.cde.config.Config

import scala.annotation.unused

/*----------------- Rocket Basic ---------------*/
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketS1 extends Config(
  new WithNSmallCores(1)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketS2 extends Config(
  new WithNSmallCores(2)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketM1 extends Config(
  new WithNMedCores(1)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketM2 extends Config(
  new WithNMedCores(2)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketB1 extends Config(
  new WithNBigCores(1)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketB2 extends Config(
  new WithNBigCores(2)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketB4 extends Config(
  new WithNBigCores(4)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketH1 extends Config(
  new WithNHugeCores(1)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketH2 extends Config(
  new WithNHugeCores(2)
)