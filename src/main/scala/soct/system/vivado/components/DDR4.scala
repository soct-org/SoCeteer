package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.fpga.{DDR4Port, FPGAClockPort, FPGAResetPortSource}
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}
import soct.system.vivado.abstracts._

import scala.collection.mutable


/**
 * DDR4 memory controller component for Xilinx FPGAs.
 */
case class DDR4()(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip with ConnectOps {

  override def partName: String = "xilinx.com:ip:ddr4:2.2"

  object C0_DDR4 extends BdIntfPin("C0_DDR4", this)

  object C0_SYS_CLK extends BdIntfPin("C0_SYS_CLK", this) with DrivenByNet

  object SYS_RST extends BdPinIn("sys_rst", this)

  case class ADDN_UI_CLKOUT_I(idx: Int, dom: ClockDomain) extends BdPinOut(s"addn_ui_clkout$idx", DDR4.this)

  // Helper functions to create interfaces with multiple instances:
  private val addn_ui_clkouts: mutable.Map[Int, ADDN_UI_CLKOUT_I] = mutable.Map.empty
  def ADDN_UI_CLKOUT(idx: Int, dom: ClockDomain): ADDN_UI_CLKOUT_I = {
    require(idx >= 1 && idx <= 4, s"DDR4 ADDN_UI_CLKOUT index must be between 1 and 4, got $idx")
    addn_ui_clkouts.getOrElseUpdate(idx, ADDN_UI_CLKOUT_I(idx, dom))
  }


  override def defaultProperties: Map[String, String] = {
    val m = mutable.Map.empty[String, String]
    val ddr4Intf = bd.getConnector(C0_DDR4, p => p.isInstanceOf[DDR4Port])
    val boardClk = bd.getConnector(C0_SYS_CLK, p => p.isInstanceOf[FPGAClockPort])
    val boardRst = bd.getConnector(SYS_RST, p => p.isInstanceOf[FPGAResetPortSource])
    m += "CONFIG.C0_DDR4_BOARD_INTERFACE" -> ddr4Intf.ref
    m += "CONFIG.C0_CLOCK_BOARD_INTERFACE" -> boardClk.ref
    m += "CONFIG.RESET_BOARD_INTERFACE" -> boardRst.ref

    addn_ui_clkouts.foreach {
      case (idx, clk) =>
        m += s"CONFIG.ADDN_UI_CLKOUT${idx}_FREQ_HZ" -> clk.dom.freqMHz.toInt.toString
    }

    m.toMap
  }
}


object DDR4 {
  implicit val a: AutoConnect[DDR4, DDR4Port] = (comp: DDR4, port: DDR4Port, bd: SOCTBdBuilder) =>
    bd.connect(comp.C0_DDR4, port)
}