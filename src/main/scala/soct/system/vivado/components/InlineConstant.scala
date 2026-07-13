package soct.system.vivado.components

import chisel3.UInt
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.abstracts.BdPinPort.portToBdPin
import soct.system.vivado.{SOCTBdBuilder, TCLCommands}
import soct.system.vivado.abstracts._

import scala.annotation.unused


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


/** All-zeros constant, for tying inputs low. */
object TieOff {
  /**
   * @param nBits the number of bits
   * @return an [[InlineConstant]] of `nBits` zeros
   */
  def apply(nBits: Int = 1)(implicit bd: SOCTBdBuilder, p: Parameters): InlineConstant = {
    InlineConstant(0, nBits)
  }
}

/** All-ones constant, for tying inputs high. */
@unused // component library
object TieHigh {
  /**
   * @param nBits the number of bits
   * @return an [[InlineConstant]] of `nBits` ones
   */
  def apply(nBits: Int = 1)(implicit bd: SOCTBdBuilder, p: Parameters): InlineConstant = {
    val maxValue = (BigInt(1) << nBits) - 1
    InlineConstant(maxValue, nBits)
  }
}

object InlineConstant {

  /**
   * Create a constant from a Chisel literal.
   *
   * @param value the Chisel literal providing the constant value
   * @param nBits the number of bits
   * @return the constant component
   */
  def apply(value: UInt, nBits: Int)(implicit bd: SOCTBdBuilder, p: Parameters): InlineConstant = {
    InlineConstant(value.litValue, nBits)
  }

  implicit def doutIsDefaultSrcChisel[T <: chisel3.Data]: ToSinkConnect[InlineConstant, T] = (comp: InlineConstant, sink: T, bd: SOCTBdBuilder) => {
    bd.addEdge(comp.DOUT, portToBdPin(sink)(bd))
  }

  implicit def doutIsDefaultSrcBdPinPort: ToSinkConnect[InlineConstant, BdPinPort] = (comp: InlineConstant, sink: BdPinPort, bd: SOCTBdBuilder) => {
    bd.addEdge(comp.DOUT, sink)
  }

}
