package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts._


case class BSCAN()(implicit bd: SOCTBdBuilder, p: Parameters) extends BdComp
  with Xip with HasConnect[BSCAN] {

  override def partName: String = "xilinx.com:ip:debug_bridge:3.0"

  override def defaultProperties: Map[String, String] =  {
    // Count number of M_BSCAN sources
    val nSlaves = bd.connectsWithProperty((source, _) => source.isInstanceOf[M_BSCAN]).size
    Map(
      "CONFIG.C_DEBUG_MODE" -> "7", // JTAG-to-AXI, ChipScope, or JTAG-to-JTAG bridge
      "CONFIG.C_USER_SCAN_CHAIN" -> "1", // One user scan chain
      "CONFIG.C_NUM_BS_MASTER" -> nSlaves.toString // Number of BSCAN slaves
    )
  }

  case class M_BSCAN(i: Int) extends BdIntfPin(s"m${i}_bscan", BSCAN.this)
  object M0_BSCAN extends M_BSCAN(0) {
    // Throw warning if this is already connected
    if (bd.numSinks(this) > 0) {
      soct.log.warn(s"BSCAN M0_BSCAN interface is already connected to another component, is this intended?")
    }
  }
}

object BSCAN {
  implicit val a: ToSinkConnect[BSCAN, BSCAN2JTAG] = (comp: BSCAN, sink: BSCAN2JTAG, bd: SOCTBdBuilder) =>
    bd.connect(comp.M0_BSCAN, sink.S_BSCAN) // By default, only connect the first BSCAN port
}