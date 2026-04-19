package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts._

/**
 * Result of an inline vector logic operation, containing the component and the output pin to connect to
 * @param c the inline vector logic component instance
 * @param r the output pin of the component to connect to
 */
case class Result(c: InlineVectorLogic, r: BdPinOut)

/**
 * Base inline vector logic (hidden from users)
 */
sealed abstract class InlineVectorLogic (op: String, width: Int)(implicit bd: SOCTBdBuilder, p: Parameters)
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
  def apply(width: Int, op1: => DrivesNet, op2: => DrivesNet, name: String)(implicit bd: SOCTBdBuilder, p: Parameters): Result = {
    val andComp = AND(width).withInstanceName(name)
    op1 --> andComp.OP1
    op2 --> andComp.OP2
    Result(andComp, andComp.RES)
  }
  // apply with one bit width for convenience
  def apply(op1: => DrivesNet, op2: => DrivesNet, name: String)(implicit bd: SOCTBdBuilder, p: Parameters): Result = apply(1, op1, op2, name)
}

final case class OR(width: Int)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BinaryVectorLogic("or", width)

object OR {
  def apply(width: Int, op1: => DrivesNet, op2: => DrivesNet, name: String)(implicit bd: SOCTBdBuilder, p: Parameters): Result = {
    val orComp = OR(width).withInstanceName(name)
    op1 --> orComp.OP1
    op2 --> orComp.OP2
    Result(orComp, orComp.RES)
  }
  // apply with one bit width for convenience
  def apply(op1: => DrivesNet, op2: => DrivesNet, name: String)(implicit bd: SOCTBdBuilder, p: Parameters): Result = apply(1, op1, op2, name)
}

final case class XOR(width: Int)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BinaryVectorLogic("xor", width)


object XOR {
  def apply(width: Int, op1: => DrivesNet, op2: => DrivesNet, name: String)(implicit bd: SOCTBdBuilder, p: Parameters): Result = {
    val xorComp = XOR(width).withInstanceName(name)
    op1 --> xorComp.OP1
    op2 --> xorComp.OP2
    Result(xorComp, xorComp.RES)
  }
  // apply with one bit width for convenience
  def apply(op1: => DrivesNet, op2: => DrivesNet, name: String)(implicit bd: SOCTBdBuilder, p: Parameters): Result = apply(1, op1, op2, name)
}

final case class NOT(width: Int)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends InlineVectorLogic("not", width)

object NOT {
  def apply(width: Int, op: => DrivesNet, name: String)(implicit bd: SOCTBdBuilder, p: Parameters): Result = {
    val notComp = NOT(width).withInstanceName(name)
    op --> notComp.OP1
    Result(notComp, notComp.RES)
  }
  // apply with one bit width for convenience
  def apply(op: => DrivesNet, name: String)(implicit bd: SOCTBdBuilder, p: Parameters): Result = apply(1, op, name)
}