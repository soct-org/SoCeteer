package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts._


case class BSCAN()(implicit bd: SOCTBdBuilder, p: Parameters) extends BdComp()(bd, p, None)
  with Xip with HasAutoConnect[BSCAN] {

  override def partName: String = "xilinx.com:ip:debug_bridge:3.0"

  override def defaultProperties: Map[String, String] =  {
    val nSlaves = M_BSCAN.getIOs.size
    Map(
      "CONFIG.C_DEBUG_MODE" -> "7", // JTAG-to-AXI, ChipScope, or JTAG-to-JTAG bridge
      "CONFIG.C_USER_SCAN_CHAIN" -> "1", // One user scan chain
      "CONFIG.C_NUM_BS_MASTER" -> s"$nSlaves" // Number of BSCAN slaves
    )
  }

  object M_BSCAN extends Source {}

}


object BSCAN {
  def outPort(i: Int): String = s"m${i}_bscan"

  implicit val a: AutoConnect[BSCAN, BSCAN2JTAG] = (comp: BSCAN, port: BSCAN2JTAG) => comp.M_BSCAN.add(port.S_BSCAN)

}