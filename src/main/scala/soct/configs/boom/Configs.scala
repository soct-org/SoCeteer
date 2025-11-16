package soct
import org.chipsalliance.cde.config.Config

/*----------------- Boom v3 ---------------*/

class SmallBoomV3 extends Config(
  new boom.v3.common.WithNSmallBooms(1)
)

class DualSmallBoomV3 extends Config(
  new boom.v3.common.WithNSmallBooms(2)
)

class MediumBoomV3 extends Config(
  new boom.v3.common.WithNMediumBooms(1)
)

class DualMediumBoomV3 extends Config(
  new boom.v3.common.WithNMediumBooms(2)
)

class LargeBoomV3 extends Config(
  new boom.v3.common.WithNLargeBooms(1)
)

class DualLargeBoomV3 extends Config(
  new boom.v3.common.WithNLargeBooms(2)
)

class MegaBoomV3 extends Config(
  new boom.v3.common.WithNMegaBooms(1)
)

class DualMegaBoomV3 extends Config(
  new boom.v3.common.WithNMegaBooms(2)
)

/*----------------- Boom v4 ---------------*/

class SmallBoomV4 extends Config(
  new boom.v4.common.WithNSmallBooms(1)
)

class DualSmallBoomV4 extends Config(
  new boom.v4.common.WithNSmallBooms(2)
)

class MediumBoomV4 extends Config(
  new boom.v4.common.WithNMediumBooms(1)
)

class DualMediumBoomV4 extends Config(
  new boom.v4.common.WithNMediumBooms(2)
)

class LargeBoomV4 extends Config(
  new boom.v4.common.WithNLargeBooms(1)
)

class DualLargeBoomV4 extends Config(
  new boom.v4.common.WithNLargeBooms(2)
)

class MegaBoomV4 extends Config(
  new boom.v4.common.WithNMegaBooms(1)
)

class DualMegaBoomV4 extends Config(
  new boom.v4.common.WithNMegaBooms(2)
)