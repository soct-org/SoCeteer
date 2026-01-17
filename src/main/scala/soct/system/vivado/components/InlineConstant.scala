package soct.system.vivado.components

import chisel3.{Data, UInt}
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, SOCTVivado}

/**
 * Constant IP core for Xilinx FPGAs
 *
 * @param value The constant value
 * @param nBits The number of bits
 */
case class InlineConstant(value: UInt,
                          nBits: Int
                         )(implicit bd: SOCTBdBuilder, p: Parameters)
  extends InstantiableBdComp with IsXilinxIP {
  override def partName: String = "xilinx.com:inline_hdl:ilconstant:1.0"

  override def friendlyName: String = s"constant_${nBits}bit_${value.litValue}"

  override def ipType: String = "inline_hdl"

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.CONST_VAL" -> s"${value.litValue}",
    "CONFIG.CONST_WIDTH" -> s"${nBits}"
  )

  val outPort = "dout" // Check doc of Inline Constant IP core for port name

  private def validReceivers: Seq[Data] = receivers.collect {
    case data: Data => data
  }.toSeq

  /**
   * Emit the TCL commands to connect this component in the design
   */
  override def connectTclCommands: Seq[String] = {
    validReceivers.map {
      data: Data => connectNet(data, outPort)
    }
  }
}