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
  extends BdComp with Xip with ConnectOps with HasIndexedPins {

  override def partName: String = "xilinx.com:ip:ddr4:2.2"

  object C0_DDR4 extends BdIntfPin("C0_DDR4", DDR4.this)

  object C0_SYS_CLK extends BdIntfPin("C0_SYS_CLK", DDR4.this) with DrivenByNet

  object C0_DDR4_UI_CLK_SYNC_RST extends BdPinOut("c0_ddr4_ui_clk_sync_rst", DDR4.this)

  object C0_DDR4_UI_CLK extends BdPinOut("c0_ddr4_ui_clk", DDR4.this)

  object C0_DDR4_ARESETN extends BdPinIn("c0_ddr4_aresetn", DDR4.this)

  object SYS_RST extends BdPinIn("sys_rst", DDR4.this)

  object C0_DDR4_S_AXI extends BdIntfPin("C0_DDR4_S_AXI", DDR4.this) with DrivesNet

  case class ADDN_UI_CLKOUT_I(idx: Int, dom: ClockDomain) extends BdPinOut(s"addn_ui_clkout$idx", DDR4.this)
  object ADDN_UI_CLKOUT extends IndexedPinFactory[ADDN_UI_CLKOUT_I, ClockDomain](
    indexRange = (1, 4),
    pinConstructor = (idx, dom) => ADDN_UI_CLKOUT_I(idx, dom)
  )

  override def defaultProperties: Map[String, String] = {
    val m = mutable.Map.empty[String, String]
    val ddr4Intf = bd.singleConnector(C0_DDR4, p => p.isInstanceOf[DDR4Port])
    val boardClk = bd.singleConnector(C0_SYS_CLK, p => p.isInstanceOf[FPGAClockPort])
    val boardRst = bd.singleConnector(SYS_RST, p => p.isInstanceOf[FPGAResetPortSource])
    m += "CONFIG.C0_DDR4_BOARD_INTERFACE" -> ddr4Intf.ref
    m += "CONFIG.C0_CLOCK_BOARD_INTERFACE" -> boardClk.ref
    m += "CONFIG.RESET_BOARD_INTERFACE" -> boardRst.ref

    ADDN_UI_CLKOUT.all.foreach {
      case (idx, clk) =>
        m += s"CONFIG.ADDN_UI_CLKOUT${idx}_FREQ_HZ" -> clk.dom.freqMHz.toInt.toString
    }

    m.toMap
  }
}


object DDR4 {
  implicit val a: AutoConnect[DDR4, DDR4Port] = (comp: DDR4, port: DDR4Port, bd: SOCTBdBuilder) =>
    bd.addEdge(comp.C0_DDR4, port)
}