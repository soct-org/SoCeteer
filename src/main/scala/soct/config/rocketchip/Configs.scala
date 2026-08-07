package soct

import freechips.rocketchip.rocket.{WithNBigCores, WithNBreakpoints, WithNHugeCores, WithNMedCores, WithNSmallCores}
import org.chipsalliance.cde.config.Config

import scala.annotation.unused

/*----------------- Rocket Basic ---------------*/
/** One Rocket core at the 'small' size point (no FPU, no virtual memory). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketS1 extends Config(
  new WithNSmallCores(1)
)

/** Two Rocket cores at the 'small' size point (no FPU, no virtual memory). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketS2 extends Config(
  new WithNSmallCores(2)
)

/** One Rocket core at the 'medium' size point. */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketM1 extends Config(
  new WithNMedCores(1)
)

/** Two Rocket cores at the 'medium' size point. */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketM2 extends Config(
  new WithNMedCores(2)
)

/** One Rocket core at the 'big' (full-featured, Linux-capable) size point. */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketB1 extends Config(
  new WithNBigCores(1)
)

/** Two Rocket cores at the 'big' (full-featured, Linux-capable) size point. */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketB2 extends Config(
  new WithNBigCores(2)
)

/** Four Rocket cores at the 'big' (full-featured, Linux-capable) size point. */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketB4 extends Config(
  new WithNBigCores(4)
)

/** One Rocket core at the 'huge' size point. */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketH1 extends Config(
  new WithNHugeCores(1)
)

/** Two Rocket cores at the 'huge' size point. */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketH2 extends Config(
  new WithNHugeCores(2)
)