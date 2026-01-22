package soct.system.vivado.components

import freechips.rocketchip.subsystem.{CanHaveMasterAXI4MemPort, ExtMem}
import org.apache.commons.lang3.NotImplementedException
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTVivado.{DEFAULT_MEMORY_ADDR_32, DEFAULT_MEMORY_ADDR_64}
import soct.system.vivado.fpga.{DDR4Port, FPGAClockDomain}
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}
import soct.HasSOCTConfig


case class DDR4(
                 ddr4Intf: DDR4Port,
                 addnClkOut1: Option[ClockDomain] = None,
                 addnClkOut2: Option[ClockDomain] = None,
                 addnClkOut3: Option[ClockDomain] = None,
                 addnClkOut4: Option[ClockDomain] = None
               )(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain])
  extends InstantiableBdComp with IsXilinxIP {

  override def partName: String = "xilinx.com:ip:ddr4:2.2"

  override def checkAvailable(): Unit = {
    super.checkAvailable()
    val top = p(HasSOCTConfig).topModule

    val extMemOpt = p(ExtMem)
    if (extMemOpt.isEmpty) {
      throw XilinxDesignException("Adding DDR4 requires ExtMem to be defined in the Parameters")
    }

    val extMem = extMemOpt.get
    val memOffset = extMem.master.base
    if (!(memOffset == DEFAULT_MEMORY_ADDR_64 || memOffset == DEFAULT_MEMORY_ADDR_32)) {
      throw XilinxDesignException(
        s"Adding DDR4 requires ExtMem base address to be either 0x${DEFAULT_MEMORY_ADDR_64}%x or 0x${DEFAULT_MEMORY_ADDR_32}%x, got 0x${memOffset}%x"
      )
    }

    // DDR4 requires a master AXI4 memory port
    val hasMemPort = top.fold(_ => false, cls => classOf[CanHaveMasterAXI4MemPort].isAssignableFrom(cls))
    if (!hasMemPort)
      throw XilinxDesignException("Top must mix in CanHaveMasterAXI4MemPort")
  }


  private def outFreqs(): Map[String, String] = {
    var freqs = Map.empty[String, String]
    if (addnClkOut1.isDefined) {
      freqs += s"CONFIG.ADDN_UI_CLKOUT1_FREQ_HZ" -> addnClkOut1.get.freqMHz.toInt.toString
    }
    if (addnClkOut2.isDefined) {
      freqs += s"CONFIG.ADDN_UI_CLKOUT2_FREQ_HZ" -> addnClkOut2.get.freqMHz.toInt.toString
    }
    if (addnClkOut3.isDefined) {
      freqs += s"CONFIG.ADDN_UI_CLKOUT3_FREQ_HZ" -> addnClkOut3.get.freqMHz.toInt.toString
    }
    if (addnClkOut4.isDefined) {
      freqs += s"CONFIG.ADDN_UI_CLKOUT4_FREQ_HZ" -> addnClkOut4.get.freqMHz.toInt.toString
    }
    freqs
  }

  override def defaultProperties: Map[String, String] = {
    if (receivers.exists(r => !r.isInstanceOf[ClkWiz])) {
      throw new NotImplementedException(s"DDR4 can only output to ClkWiz components for now")
    }
    require(dom.isDefined && dom.get.isInstanceOf[FPGAClockDomain], s"DDR4 component must be instantiated within the FPGA clock domain for now")
    require(dom.get.reset.isDefined, s"DDR4 component requires a reset signal in its clock domain for now")

    Map(
      "CONFIG.C0_DDR4_BOARD_INTERFACE" -> ddr4Intf.ddr4Port,
      "CONFIG.C0_CLOCK_BOARD_INTERFACE" -> dom.get.name,
      "CONFIG.RESET_BOARD_INTERFACE" -> dom.get.reset.get.name
    ) ++ outFreqs
  }

  /**
   * Emit the TCL commands to connect this component in the design
   */
  override def connectTclCommands: Seq[String] =
    for {
      (opt, idx) <- Seq(addnClkOut1, addnClkOut2, addnClkOut3, addnClkOut4).zipWithIndex
      cd <- opt.toList
      port <- cd.clkReceiverPorts.flatMap(_._2())
      clkoutIdx = idx + 1
    } yield s"connect_bd_net [get_bd_pins ${this.instanceName}/addn_ui_clkout$clkoutIdx] [get_bd_pins $port]"
}


case object DDR4 {
  val C0_DDR4 = "C0_DDR4"

  val SyncReset = "c0_ddr4_ui_clk_sync_rst"
}
