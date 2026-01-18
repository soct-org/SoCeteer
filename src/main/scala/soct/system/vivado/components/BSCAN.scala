package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder


case class BSCAN()(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain] = None) // Clock not needed
  extends InstantiableBdComp with IsXilinxIP {

  override def partName: String = "xilinx.com:ip:debug_bridge:3.0"

  def outPort(i: Int): String = s"m${i}_bscan" // Check doc of Debug Bridge IP core for port name

  private def validReceivers: Seq[BSCAN2JTAG] = receivers.collect {
    case b2j: BSCAN2JTAG => b2j
  }.toSeq

  override def defaultProperties: Map[String, String] =  {
    val nSlaves = validReceivers.length
    Map(
      "CONFIG.C_DEBUG_MODE" -> "7", // JTAG-to-AXI, ChipScope, or JTAG-to-JTAG bridge
      "CONFIG.C_USER_SCAN_CHAIN" -> "1", // One user scan chain
      "CONFIG.C_NUM_BS_MASTER" -> s"$nSlaves" // Number of BSCAN slaves
    )
  }

  /**
   * Emit the TCL commands to connect this component in the design
   */
  override def connectTclCommands: Seq[String] = {
    validReceivers.zipWithIndex.map { case (jtag, idx) =>
      s"connect_bd_intf_net [get_bd_intf_pins $instanceName/${outPort(idx)}] [get_bd_intf_pins ${jtag.instanceName}/${jtag.bscanIntf}]"
    }
  }
}