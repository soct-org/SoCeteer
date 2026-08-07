package soct
import org.chipsalliance.cde.config.Config

import scala.annotation.unused

/*----------------- Boom v3 ---------------*/

/** One 1-wide superscalar BOOM v3 core ('small'). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class SmallBoomV3 extends Config(
  new boom.v3.common.WithNSmallBooms(1)
)

/** Two 1-wide superscalar BOOM v3 cores ('small'). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class DualSmallBoomV3 extends Config(
  new boom.v3.common.WithNSmallBooms(2)
)

/** One 2-wide superscalar BOOM v3 core ('medium'). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class MediumBoomV3 extends Config(
  new boom.v3.common.WithNMediumBooms(1)
)

/** Two 2-wide superscalar BOOM v3 cores ('medium'). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class DualMediumBoomV3 extends Config(
  new boom.v3.common.WithNMediumBooms(2)
)

/** One 3-wide superscalar BOOM v3 core ('large'). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class LargeBoomV3 extends Config(
  new boom.v3.common.WithNLargeBooms(1)
)

/** Two 3-wide superscalar BOOM v3 cores ('large'). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class DualLargeBoomV3 extends Config(
  new boom.v3.common.WithNLargeBooms(2)
)

/** One 4-wide superscalar BOOM v3 core ('mega'). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class MegaBoomV3 extends Config(
  new boom.v3.common.WithNMegaBooms(1)
)

/** Two 4-wide superscalar BOOM v3 cores ('mega'). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class DualMegaBoomV3 extends Config(
  new boom.v3.common.WithNMegaBooms(2)
)

/*----------------- Boom v4 ---------------*/

/** One 1-wide superscalar BOOM v4 core ('small'). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class SmallBoomV4 extends Config(
  new boom.v4.common.WithNSmallBooms(1)
)

/** Two 1-wide superscalar BOOM v4 cores ('small'). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class DualSmallBoomV4 extends Config(
  new boom.v4.common.WithNSmallBooms(2)
)

/** One 2-wide superscalar BOOM v4 core ('medium'). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class MediumBoomV4 extends Config(
  new boom.v4.common.WithNMediumBooms(1)
)

/** Two 2-wide superscalar BOOM v4 cores ('medium'). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class DualMediumBoomV4 extends Config(
  new boom.v4.common.WithNMediumBooms(2)
)

/** One 3-wide superscalar BOOM v4 core ('large'). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class LargeBoomV4 extends Config(
  new boom.v4.common.WithNLargeBooms(1)
)

/** Two 3-wide superscalar BOOM v4 cores ('large'). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class DualLargeBoomV4 extends Config(
  new boom.v4.common.WithNLargeBooms(2)
)

/** One 4-wide superscalar BOOM v4 core ('mega'). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class MegaBoomV4 extends Config(
  new boom.v4.common.WithNMegaBooms(1)
)

/** Two 4-wide superscalar BOOM v4 cores ('mega'). */
@unused // --config entry point, instantiated by name via reflection (see SOCTUtils.instantiateConfig)
class DualMegaBoomV4 extends Config(
  new boom.v4.common.WithNMegaBooms(2)
)