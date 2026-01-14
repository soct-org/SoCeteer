package soct.xilinx.components

import chisel3.{Data, UInt}
import org.chipsalliance.cde.config.Parameters
import soct.ChiselTop
import soct.xilinx.BDBuilder
import soct.xilinx.SOCTVivado.toXilinxPortRef

/**
 * Constant IP core for Xilinx FPGAs
 *
 * @param value      The constant value
 * @param nBits      The number of bits
 * @param chiselData Whether to connect to a chisel port.
 *                   If None, this component must be connected to and will not generate connect TCL commands.
 */
case class InlineConstant(value: UInt,
                          nBits: Int,
                          chiselData: Option[Data] = None
                   )(implicit bd: BDBuilder, p: Parameters)
  extends InstantiableBdComp with IsXilinxIP {
  override def partName: String = "xilinx.com:inline_hdl:ilconstant:1.0"

  override def friendlyName: String = s"constant_${nBits}bit_${value.litValue}"

  val outPort: String = "dout"

  /**
   * Emit the TCL commands to connect this component in the design
   */
  override def connectTclCommands: Seq[String] = {
    chiselData match {
      case Some(data) =>
        val ref = toXilinxPortRef(data)
        Seq(
          s"connect_bd_net [get_bd_pins $instanceName/$outPort] [get_bd_pins $ref]"
        )
      case None =>
        Seq.empty
    }
  }
}