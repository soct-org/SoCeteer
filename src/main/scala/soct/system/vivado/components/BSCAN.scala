package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.components.BSCAN.outPort
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}


case class BSCAN()(implicit bd: SOCTBdBuilder, p: Parameters) extends InstantiableBdComp()(bd, p, None)
  with IsXilinxIP with SourceForPins {

  override def partName: String = "xilinx.com:ip:debug_bridge:3.0"

  override def defaultProperties: Map[String, String] =  {
    val nSlaves = sinkPins.size
    Map(
      "CONFIG.C_DEBUG_MODE" -> "7", // JTAG-to-AXI, ChipScope, or JTAG-to-JTAG bridge
      "CONFIG.C_USER_SCAN_CHAIN" -> "1", // One user scan chain
      "CONFIG.C_NUM_BS_MASTER" -> s"$nSlaves" // Number of BSCAN slaves
    )
  }

  override def connectTclCommands: Seq[String] = {
    sinkPins.zipWithIndex.map {
      case (sinkPin: BdIntfPin, i) =>
        s"connect_bd_intf_net [get_bd_intf_pins ${outPort(i + 1)}] [get_bd_intf_pins $sinkPin]" // TODO do something smarter
      case _ => throw XilinxDesignException("BSCAN only supports BdIntfPin sink pins")
    }.toSeq
  }
}


object BSCAN {
  def outPort(i: Int): String = s"m${i}_bscan"
}