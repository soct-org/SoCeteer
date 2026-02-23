package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts._

/**
 * Base inline vector logic (hidden from users)
 */
sealed abstract class InlineVectorLogic private[components](op: String, width: Int)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with XInlineHDL with ConnectOps {

  require(width > 0, s"InlineVectorLogic width must be > 0 (got $width)")

  override def partName: String =
    "xilinx.com:inline_hdl:ilvector_logic:1.0"

  override val friendlyName: String =
    s"${op}_vector_logic_${width}bit"

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.C_OPERATION" -> op,
    "CONFIG.C_SIZE" -> width.toString
  )

  object OP1 extends BdPinIn("Op1", this)

  object RES extends BdPinOut("Res", this)
}

/**
 * Binary operators expose OP2
 */
sealed abstract class BinaryVectorLogic(op: String, width: Int)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends InlineVectorLogic(op, width) {

  object OP2 extends BdPinIn("Op2", this)
}


// ---------------- Concrete Types ----------------
final case class AND(width: Int)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BinaryVectorLogic("and", width)

object AND {
  def apply(width: Int, op1: => DrivesNet, op2: => DrivesNet)(implicit bd: SOCTBdBuilder, p: Parameters): DrivesNet = {
    val andComp = AND(width)
    op1 --> andComp.OP1
    op2 --> andComp.OP2
    andComp.RES
  }
}

final case class OR(width: Int)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BinaryVectorLogic("or", width)

object OR {
  def apply(width: Int, op1: => DrivesNet, op2: => DrivesNet)(implicit bd: SOCTBdBuilder, p: Parameters): DrivesNet = {
    val orComp = OR(width)
    op1 --> orComp.OP1
    op2 --> orComp.OP2
    orComp.RES
  }
}

final case class XOR(width: Int)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BinaryVectorLogic("xor", width)


object XOR {
  def apply(width: Int, op1: => DrivesNet, op2: => DrivesNet)(implicit bd: SOCTBdBuilder, p: Parameters): DrivesNet = {
    val xorComp = XOR(width)
    op1 --> xorComp.OP1
    op2 --> xorComp.OP2
    xorComp.RES
  }
}

final case class NOT(width: Int)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends InlineVectorLogic("not", width)

object NOT {
  def apply(width: Int, op: => DrivesNet)(implicit bd: SOCTBdBuilder, p: Parameters): DrivesNet = {
    val notComp = NOT(width)
    op --> notComp.OP1
    notComp.RES
  }
}