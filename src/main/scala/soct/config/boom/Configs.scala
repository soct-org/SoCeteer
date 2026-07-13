package soct
import org.chipsalliance.cde.config.Config

import scala.annotation.unused

/*----------------- Boom v3 ---------------*/

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class SmallBoomV3 extends Config(
  new boom.v3.common.WithNSmallBooms(1)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class DualSmallBoomV3 extends Config(
  new boom.v3.common.WithNSmallBooms(2)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class MediumBoomV3 extends Config(
  new boom.v3.common.WithNMediumBooms(1)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class DualMediumBoomV3 extends Config(
  new boom.v3.common.WithNMediumBooms(2)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class LargeBoomV3 extends Config(
  new boom.v3.common.WithNLargeBooms(1)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class DualLargeBoomV3 extends Config(
  new boom.v3.common.WithNLargeBooms(2)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class MegaBoomV3 extends Config(
  new boom.v3.common.WithNMegaBooms(1)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class DualMegaBoomV3 extends Config(
  new boom.v3.common.WithNMegaBooms(2)
)

/*----------------- Boom v4 ---------------*/

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class SmallBoomV4 extends Config(
  new boom.v4.common.WithNSmallBooms(1)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class DualSmallBoomV4 extends Config(
  new boom.v4.common.WithNSmallBooms(2)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class MediumBoomV4 extends Config(
  new boom.v4.common.WithNMediumBooms(1)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class DualMediumBoomV4 extends Config(
  new boom.v4.common.WithNMediumBooms(2)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class LargeBoomV4 extends Config(
  new boom.v4.common.WithNLargeBooms(1)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class DualLargeBoomV4 extends Config(
  new boom.v4.common.WithNLargeBooms(2)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class MegaBoomV4 extends Config(
  new boom.v4.common.WithNMegaBooms(1)
)

@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class DualMegaBoomV4 extends Config(
  new boom.v4.common.WithNMegaBooms(2)
)