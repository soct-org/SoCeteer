package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts.BdPinPort.portToBdPin
import soct.system.vivado.abstracts._

/**
 * Base inline vector logic
 */
abstract class InlineVectorLogic(op: String, width: Int, connectOnInit: Boolean = true)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with XInlineHDL with ConnectOps with HasIndexedPins {

  val ops: Seq[DrivesNet]

  require(width > 0, s"InlineVectorLogic width must be > 0 (got $width)")

  override def partName: String =
    "xilinx.com:inline_hdl:ilvector_logic:1.0"

  override val friendlyName: String =
    s"${op}_vector_logic_${width}bit"

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.C_OPERATION" -> op,
    "CONFIG.C_SIZE" -> width.toString
  )

  case class OP_I(idx: Int) extends BdPinIn(s"Op$idx", this)

  object OP extends SimpleIndexedPinFactory[OP_I](
    indexRange = (1, 64), // TODO how many inputs can we support?
    pinConstructor = idx => OP_I(idx)
  )

  object RES extends BdPinOut("Res", this)

  if (connectOnInit) {
    ops.zipWithIndex.foreach { case (op, i) =>
      op --> OP(i + 1)
    }
  }

}

object InlineVectorLogic {
  implicit def resIsDefaultSrcChisel[T <: chisel3.Data]: ToSinkConnect[InlineVectorLogic, T] = (comp: InlineVectorLogic, sink: T, bd: SOCTBdBuilder) => {
    bd.addEdge(comp.RES, portToBdPin(sink)(bd))
  }

  implicit def resIsDefaultSrcBdPinPort: ToSinkConnect[InlineVectorLogic, BdPinPort] = (comp: InlineVectorLogic, sink: BdPinPort, bd: SOCTBdBuilder) => {
    bd.addEdge(comp.RES, sink)
  }
}


// ---------------- Concrete Types ----------------
final case class AND(ops: DrivesNet*)(implicit bd: SOCTBdBuilder, p: Parameters) extends InlineVectorLogic("and", 1)

final case class OR(ops: DrivesNet*)(implicit bd: SOCTBdBuilder, p: Parameters) extends InlineVectorLogic("or", 1)

final case class XOR(ops: DrivesNet*)(implicit bd: SOCTBdBuilder, p: Parameters) extends InlineVectorLogic("xor", 1)

final case class NOT(ops: DrivesNet*)(implicit bd: SOCTBdBuilder, p: Parameters) extends InlineVectorLogic("not", 1)