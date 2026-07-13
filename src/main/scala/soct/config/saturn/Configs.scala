package soct

import org.chipsalliance.cde.config.Config

import scala.annotation.unused


/*----------------- Saturn Basic ---------------*/
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class RocketB1Saturn extends Config(
  new RocketB1().orElse(
    new saturn.shuttle.WithShuttleVectorUnit(128, 128, saturn.common.VectorParams.genParams))
)