// Every config in this file is a `--config` entry point: users name them on the command
// line and SOCTUtils.instantiateConfig builds them by reflection, so nothing here is
// referenced from the repository itself.
package soct
import org.chipsalliance.cde.config.Config

/*----------------- Boom v4 ---------------*/

/** One 1-wide superscalar BOOM v4 core ('small'). */
class SmallBoomV4 extends Config(
  new boom.v4.common.WithNSmallBooms(1)
)

/** Two 1-wide superscalar BOOM v4 cores ('small'). */
class DualSmallBoomV4 extends Config(
  new boom.v4.common.WithNSmallBooms(2)
)

/** One 2-wide superscalar BOOM v4 core ('medium'). */
class MediumBoomV4 extends Config(
  new boom.v4.common.WithNMediumBooms(1)
)

/** Two 2-wide superscalar BOOM v4 cores ('medium'). */
class DualMediumBoomV4 extends Config(
  new boom.v4.common.WithNMediumBooms(2)
)

/** One 3-wide superscalar BOOM v4 core ('large'). */
class LargeBoomV4 extends Config(
  new boom.v4.common.WithNLargeBooms(1)
)

/** Two 3-wide superscalar BOOM v4 cores ('large'). */
class DualLargeBoomV4 extends Config(
  new boom.v4.common.WithNLargeBooms(2)
)

/** One 4-wide superscalar BOOM v4 core ('mega'). */
class MegaBoomV4 extends Config(
  new boom.v4.common.WithNMegaBooms(1)
)

/** Two 4-wide superscalar BOOM v4 cores ('mega'). */
class DualMegaBoomV4 extends Config(
  new boom.v4.common.WithNMegaBooms(2)
)