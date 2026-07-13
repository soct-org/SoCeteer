package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand}
import soct.system.vivado.abstracts._


/**
 * Xilinx Debug Bridge IP in BSCAN mode: exposes the FPGA's JTAG user scan chain as `m<i>_bscan`
 * master interfaces (e.g. towards a [[BSCAN2JTAG]] bridge for the RISC-V debug module).
 * Documentation: https://docs.amd.com/r/en-US/pg245-debug-bridge
 *
 * @param debugMode the IP's C_DEBUG_MODE (default 7 = JTAG-to-AXI, ChipScope, or JTAG-to-JTAG bridge)
 */
case class BSCAN(debugMode: Int = 7)(implicit bd: SOCTBdBuilder, p: Parameters) extends BdComp
  with Xip with ConnectOps with HasIndexedPins with Finalizable {

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
      "CONFIG.C_DEBUG_MODE" -> debugMode.toString,
      "CONFIG.C_USER_SCAN_CHAIN" -> "1", // One user scan chain
      "CONFIG.C_NUM_BS_MASTER" -> nSlaves.toString // Number of BSCAN slaves
    )
  }

  override protected def finalizeBdImpl(): Unit = {
    // Demote Chipscope 16-336 to warning
    bd.addConfigTcl(() => Seq("set_msg_config -id {Chipscope 16-336} -new_severity WARNING".tcl))
  }
}

object BSCAN {
  implicit val bscanToBscan2Jtag: AutoConnect[BSCAN, BSCAN2JTAG] = (comp: BSCAN, sink: BSCAN2JTAG, bd: SOCTBdBuilder) =>
    bd.addEdge(comp.M_BSCAN.getOrElseInit(0), sink.S_BSCAN) // By default, only connect the first BSCAN port
}