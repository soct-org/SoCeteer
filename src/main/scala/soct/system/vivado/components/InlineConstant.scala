package soct.system.vivado.components

import chisel3.UInt
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, TCLCommands}
import soct.system.vivado.components.InlineConstant._

/**
 * Constant IP core for Xilinx FPGAs
 *
 * @param value The constant value
 * @param nBits The number of bits
 */
case class InlineConstant(value: UInt, nBits: Int)
                         (implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain] = None) // Clock not needed
  extends InstantiableBdComp with XInlineHDL with SourceForSinks {

  override def partName: String = "xilinx.com:inline_hdl:ilconstant:1.0"

  override def friendlyName: String = s"constant_${nBits}bit_${value.litValue}"

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.CONST_VAL" -> s"${value.litValue}",
    "CONFIG.CONST_WIDTH" -> s"$nBits"
  )

  /**
   * Emit the TCL commands to connect this component in the design
   */
  protected override def connectToSinksImpl(): TCLCommands = {
    val source = BdPin(outPort, this)
    BdPinBase.connect(source, sinkPins)
  }
}

object InlineConstant {
  val outPort = "dout"
}