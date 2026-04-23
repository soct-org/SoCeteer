package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands}
import soct.system.vivado.abstracts._

import scala.collection.mutable


case class BSCAN(debugMode: Int = 7)(implicit bd: SOCTBdBuilder, p: Parameters) extends BdComp // 7 = JTAG-to-AXI, ChipScope, or JTAG-to-JTAG bridge
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
      "CONFIG.C_DEBUG_MODE" -> debugMode.toString,
      "CONFIG.C_USER_SCAN_CHAIN" -> "1", // One user scan chain
      "CONFIG.C_NUM_BS_MASTER" -> nSlaves.toString // Number of BSCAN slaves
    )
  }

  def timingTcl(clockVar: String, clockPeriodVar: String): TCLCommands = {
    // TODO add other conditions
    Seq(s"""# Timing constraints for BSCAN (Debug Bridge)
       |if { [llength [get_pins -quiet -hier SERIES7_BSCAN*/TCK]] } {
       |  # Debug Bridge is used for debugging
       |  set tck_pin [get_pins -hier SERIES7_BSCAN*/TCK]
       |}
       |
       |if { $$tck_pin != "" } {
       |  if { ![llength [get_clocks -quiet -of_objects $$tck_pin]] } {
       |    create_clock -name jtag_clock -period 15.000 $$tck_pin
       |  }
       |  set jtag_clock [get_clocks -of_objects $$tck_pin]
       |  set jtag_clock_period [get_property -min PERIOD $$jtag_clock]
       |
       |  set_max_delay -reset_path -from $$$clockVar -to $$jtag_clock -datapath_only $$jtag_clock_period
       |  set_max_delay -reset_path -from $$jtag_clock -to $$$clockVar -datapath_only $$$clockPeriodVar
       |}
       |""".stripMargin.tcl)
  }

}

object BSCAN {
  implicit val bscanToBscan2Jtag: AutoConnect[BSCAN, BSCAN2JTAG] = (comp: BSCAN, sink: BSCAN2JTAG, bd: SOCTBdBuilder) =>
    bd.addEdge(comp.M_BSCAN.getOrElseInit(0), sink.S_BSCAN) // By default, only connect the first BSCAN port
}