package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts._

import scala.collection.mutable


case class BSCAN()(implicit bd: SOCTBdBuilder, p: Parameters) extends BdComp
  with Xip with ConnectOps with HasIndexedPins {

  override def partName: String = "xilinx.com:ip:debug_bridge:3.0"

  case class M_BSCAN_I(i: Int) extends BdIntfPin(s"m${i}_bscan", BSCAN.this)
  // TODO upper limit on number of BSCAN ports?
  object M_BSCAN extends SimpleIndexedPinFactory[M_BSCAN_I](
    indexRange = (0, 16),
    pinConstructor = idx => M_BSCAN_I(idx)
  )

  override def defaultProperties: Map[String, String] =  {
    // Count number of M_BSCAN sources
    val nSlaves = M_BSCAN.all.size
    Map(
      "CONFIG.C_DEBUG_MODE" -> "7", // JTAG-to-AXI, ChipScope, or JTAG-to-JTAG bridge
      "CONFIG.C_USER_SCAN_CHAIN" -> "1", // One user scan chain
      "CONFIG.C_NUM_BS_MASTER" -> nSlaves.toString // Number of BSCAN slaves
    )
  }
}

object BSCAN {
  implicit val a: AutoConnect[BSCAN, BSCAN2JTAG] = (comp: BSCAN, sink: BSCAN2JTAG, bd: SOCTBdBuilder) =>
    bd.addEdge(comp.M_BSCAN.getOrElseInit(0), sink.S_BSCAN) // By default, only connect the first BSCAN port
}