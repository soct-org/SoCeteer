package soct.system.vivado.components

import chisel3.UInt
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, TCLCommands}
import soct.system.vivado.components.InlineConstant._
import soct.system.vivado.abstracts._


/**
 * Constant IP core for Xilinx FPGAs
 *
 * @param value The constant value
 * @param nBits The number of bits
 */
case class InlineConstant(value: UInt, nBits: Int)
                         (implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain] = None) // Clock not needed
  extends BdComp with XInlineHDL with SourceForSinks with HasSingleOutput  {

  override def partName: String = "xilinx.com:inline_hdl:ilconstant:1.0"

  override def friendlyName: String = s"constant_${nBits}bit_${value.litValue}"

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.CONST_VAL" -> s"${value.litValue}",
    "CONFIG.CONST_WIDTH" -> s"$nBits"
  )

  protected override def connectToSinksImpl: TCLCommands = {
    BdPinPort.connect(output, sinkPins)
  }

  override def output: BdPinPort = BdPin(outPort, this)
}

object InlineConstant {
  val outPort = "dout"
}