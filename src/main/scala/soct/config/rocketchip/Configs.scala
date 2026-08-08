// Every config in this file is a `--config` entry point: users name them on the command
// line and SOCTUtils.instantiateConfig builds them by reflection, so nothing here is
// referenced from the repository itself.
package soct

import freechips.rocketchip.rocket.{WithNBigCores, WithNBreakpoints, WithNHugeCores, WithNMedCores, WithNSmallCores}
import org.chipsalliance.cde.config.Config

/*----------------- Rocket Basic ---------------*/
/** One Rocket core at the 'small' size point (no FPU, no virtual memory). */
class RocketS1 extends Config(
  new WithNSmallCores(1)
)

/** Two Rocket cores at the 'small' size point (no FPU, no virtual memory). */
class RocketS2 extends Config(
  new WithNSmallCores(2)
)

/** One Rocket core at the 'medium' size point. */
class RocketM1 extends Config(
  new WithNMedCores(1)
)

/** Two Rocket cores at the 'medium' size point. */
class RocketM2 extends Config(
  new WithNMedCores(2)
)

/** One Rocket core at the 'big' (full-featured, Linux-capable) size point. */
class RocketB1 extends Config(
  new WithNBigCores(1)
)

/** Two Rocket cores at the 'big' (full-featured, Linux-capable) size point. */
class RocketB2 extends Config(
  new WithNBigCores(2)
)

/** Four Rocket cores at the 'big' (full-featured, Linux-capable) size point. */
class RocketB4 extends Config(
  new WithNBigCores(4)
)

/** One Rocket core at the 'huge' size point. */
class RocketH1 extends Config(
  new WithNHugeCores(1)
)

/** Two Rocket cores at the 'huge' size point. */
class RocketH2 extends Config(
  new WithNHugeCores(2)
)