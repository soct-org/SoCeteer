package soct.system.vivado.components

import freechips.rocketchip.subsystem.{CanHaveMasterAXI4MemPort, ExtMem}
import org.apache.commons.lang3.NotImplementedException
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTVivado.{DEFAULT_MEMORY_ADDR_32, DEFAULT_MEMORY_ADDR_64}
import soct.system.vivado.fpga.DDR4Port
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}
import soct.HasSOCTConfig


case class DDR4(ddr4Intf: DDR4Port)(implicit bd: SOCTBdBuilder, p: Parameters)
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

  private def clkWizConnects: Map[String, String] = {
    val clkWizs = receivers.collect { case cw@ClkWiz(_) => cw }
    require(clkWizs.length <= 4, s"DDR4 can drive up to 4 clock wizards, got ${clkWizs.length}")
    clkWizs.zipWithIndex.flatMap {
      case (cw, idx) =>
        if (cw.cds.nonEmpty) {
          require(cw.cds.forall(cd => cd.freqMHz <= ddr4Intf.defaultClock.freqMHz),
            s"DDR4 clock ${ddr4Intf.defaultClock.freqMHz} MHz cannot drive clock wizard output clocks higher than itself (${cw.cds.map(cd => s"${cd.name}: ${cd.freqMHz} MHz").mkString(", ")})")
          val maxFreq = cw.cds.map(_.freqMHz).max
          soct.log.debug(s"DDR4 driving clock wizard output ${idx + 1} at ${maxFreq} MHz")
          Map(s"CONFIG.ADDN_UI_CLKOUT${idx + 1}_FREQ_HZ" -> maxFreq.toInt.toString) // clkout indices are 1-based
        } else {
          Map.empty[String, String]
        }
    }.toMap
  }

  override def defaultProperties: Map[String, String] = {
    if (receivers.exists(r => !r.isInstanceOf[ClkWiz])) {
      throw new NotImplementedException(s"DDR4 can only output to ClkWiz components for now")
    }


    Map(
      "CONFIG.C0_DDR4_BOARD_INTERFACE" -> ddr4Intf.ddr4Port,
      "CONFIG.C0_CLOCK_BOARD_INTERFACE" -> ddr4Intf.defaultClock.name,
      "CONFIG.RESET_BOARD_INTERFACE" -> ddr4Intf.defaultReset
    ) ++ clkWizConnects
  }

  /**
   * Emit the TCL commands to connect this component in the design
   */
  override def connectTclCommands: Seq[String] = Seq.empty
}

