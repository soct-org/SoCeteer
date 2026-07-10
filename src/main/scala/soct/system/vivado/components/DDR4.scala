package soct.system.vivado.components

import freechips.rocketchip.subsystem.ExtMem
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.abstracts._
import soct.system.vivado.fpga.{FPGADiffClockPort, FPGAResetPortSource}
import soct.system.vivado.{DDR4Info, SOCTBdBuilder, StringToTCLCommand, TCLCommands, XilinxDesignException}

import scala.collection.mutable


/**
 * DDR4 memory controller component for Xilinx FPGAs.
 */
case class DDR4(info: DDR4Info)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip with ConnectOps with HasIndexedPins with HasBdAddr {

  override def partName: String = "xilinx.com:ip:ddr4:2.2"

  object C0_DDR4 extends BdIntfPin("C0_DDR4", DDR4.this)
  info.ddr4Intf <-> C0_DDR4

  object C0_SYS_CLK extends BdIntfPin("C0_SYS_CLK", DDR4.this) with DrivenByNet

  object C0_SYS_CLK_I extends BdPinIn("c0_sys_clk_i", DDR4.this) // The unbuffered clock version

  object C0_DDR4_UI_CLK_SYNC_RST extends BdPinOut("c0_ddr4_ui_clk_sync_rst", DDR4.this)

  object C0_INIT_CALIB_COMPLETE extends BdPinOut("c0_init_calib_complete", DDR4.this)

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
    val boardRst = bd.singleConnector(SYS_RST, p => p.isInstanceOf[FPGAResetPortSource])

    m += "CONFIG.C0_DDR4_BOARD_INTERFACE" -> info.ddr4Intf.ref
    m += "CONFIG.RESET_BOARD_INTERFACE" -> boardRst.ref
    m += "CONFIG.C0_DDR4_MEMORY_MAP_BASEADDR" -> p(ExtMem).get.master.base.toLong.toHexString

    val clkIn1Src = bd.sourceOf(C0_SYS_CLK_I)
    val clkIn1DSrc = bd.sourceOf(C0_SYS_CLK)

    if (clkIn1Src.isDefined && clkIn1DSrc.isDefined) {
      throw XilinxDesignException(s"DDR4 $instanceName C0_SYS_CLK and c0_sys_clk_i cannot both be connected to a source. Only one clock input can be used.")
    }

    (clkIn1DSrc, clkIn1Src) match {
      case (Some(c: FPGADiffClockPort), None) =>
        m += "CONFIG.System_Clock" -> "Differential"
        m += "CONFIG.C0_CLOCK_BOARD_INTERFACE" -> c.ref
      case _ =>
        soct.log.warn(s"DDR4 $instanceName C0_SYS_CLK is not connected to a differential clock source. Using unbuffered clock input c0_sys_clk_i instead.")
        m += "CONFIG.System_Clock" -> "No_Buffer"
    }


    ADDN_UI_CLKOUT.all.foreach {
      case (idx, clk) =>
        m += s"CONFIG.ADDN_UI_CLKOUT${idx}_FREQ_HZ" -> clk.dom.freqMHz.toInt.toString
    }

    m.toMap
  }

  override def assignAddrTcl: TCLCommands = {
    // assign_bd_address requires a power-of-two range, but the aperture
    // (memory base + capacity) is not one whenever capacity != base, e.g.
    // 2 GiB base + 16 GiB DIMM = 18 GiB. Round up to the next power of two;
    // the DDR4 controller only responds within [BASEADDR, BASEADDR + capacity),
    // so the oversized head/tail of the segment is never addressed.
    val aperture = BigInt(info.param.getCap.value) + p(ExtMem).get.master.base
    if (aperture <= 0) {
      throw XilinxDesignException(s"DDR4 $instanceName has a non-positive address aperture ($aperture). Check the memory capacity and ExtMem base address.")
    }
    val range = BigInt(1) << (aperture - 1).bitLength
    if (range != aperture) {
      soct.log.debug(s"DDR4 $instanceName aperture 0x${aperture.toString(16)} is not a power of two; assigning address range 0x${range.toString(16)} instead.")
    }
    Seq(
      s"assign_bd_address -offset 0 -range 0x${range.toString(16).toUpperCase} -target_address_space [get_bd_addr_spaces ${info.axiAddrSpacePin.ref}] [get_bd_addr_segs $instanceName/C0_DDR4_MEMORY_MAP/C0_DDR4_ADDRESS_BLOCK]".tcl
    )
  }
}