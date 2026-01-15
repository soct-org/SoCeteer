package soct

import org.chipsalliance.cde.config.Config


/*----------------- Saturn Basic ---------------*/
class RocketB1Saturn extends Config(
  new RocketB1().orElse(
    new saturn.shuttle.WithShuttleVectorUnit(128, 128, saturn.common.VectorParams.genParams))
)