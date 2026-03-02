package soct.system.vivado.components

import chisel3.UInt
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.abstracts.BdPinPort.portToBdPin
import soct.system.vivado.{SOCTBdBuilder, TCLCommands}
import soct.system.vivado.abstracts._


/**
 * Constant IP core for Xilinx FPGAs
 *
 * @param value The constant value
 * @param nBits The number of bits
 */
case class InlineConstant(value: BigInt, nBits: Int)
                         (implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with XInlineHDL with ConnectOps {

  override def partName: String = "xilinx.com:inline_hdl:ilconstant:1.0"

  override val friendlyName: String = s"constant_${nBits}bit_$value"

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.CONST_VAL" -> s"$value",
    "CONFIG.CONST_WIDTH" -> s"$nBits"
  )

  object DOUT extends BdPinOut("dout", InlineConstant.this)
}


object TieOff {
  def apply(nBits: Int = 1)(implicit bd: SOCTBdBuilder, p: Parameters): InlineConstant = {
    InlineConstant(0, nBits)
  }
}

object TieHigh {
  def apply(nBits: Int = 1)(implicit bd: SOCTBdBuilder, p: Parameters): InlineConstant = {
    val maxValue = (BigInt(1) << nBits) - 1
    InlineConstant(maxValue, nBits)
  }
}

object InlineConstant {

  // Apply with Uint for chisel
  def apply(value: UInt, nBits: Int)(implicit bd: SOCTBdBuilder, p: Parameters): InlineConstant = {
    InlineConstant(value.litValue, nBits)
  }

  implicit def a[T <: chisel3.Data]: ToSinkConnect[InlineConstant, T] = (comp: InlineConstant, sink: T, bd: SOCTBdBuilder) => {
    bd.addEdge(comp.DOUT, portToBdPin(sink)(bd))
  }
}
