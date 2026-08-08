// The configs in this file are `--config` entry points: users name them on the command
// line and SOCTUtils.instantiateConfig builds them by reflection, so nothing here is
// referenced from the repository itself.
package soct

import org.chipsalliance.cde.config.Config

/*----------------- Saturn Basic ---------------*/
class RocketB1Saturn extends Config(
  new RocketB1().orElse(
    new saturn.shuttle.WithShuttleVectorUnit(128, 128, saturn.common.VectorParams.genParams))
)