package soct.system.vivado.components

import freechips.rocketchip.subsystem.{CanHaveMasterAXI4MemPort, ExtMem}
import org.apache.commons.lang3.NotImplementedException
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTVivado.{DEFAULT_MEMORY_ADDR_32, DEFAULT_MEMORY_ADDR_64}
import soct.system.vivado.fpga.{DDR4Port, FPGAClockDomain, FPGAResetPort}
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}
import soct.HasSOCTConfig
import soct.system.vivado.components.DDR4._

import scala.collection.mutable

/**
 *
 * @param ddr4Intf
 * @param addnClkOut1
 * @param addnClkOut2
 * @param addnClkOut3
 * @param addnClkOut4
 * @param dom The clock domain in which this DDR4 component is instantiated - for now, must be an FPGAClockDomain
 */
case class DDR4(
                 ddr4Intf: DDR4Port,
                 addnClkOut1: Option[ClockDomain] = None,
                 addnClkOut2: Option[ClockDomain] = None,
                 addnClkOut3: Option[ClockDomain] = None,
                 addnClkOut4: Option[ClockDomain] = None
               )(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[FPGAClockDomain])
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

    val props = mutable.Map.empty[String, String]

    dom.foreach {
      case fpgaDom: FPGAClockDomain =>
        props += "CONFIG.C0_DDR4_BOARD_INTERFACE" -> ddr4Intf.portName
        props += "CONFIG.C0_CLOCK_BOARD_INTERFACE" -> fpgaDom.port.portName

        fpgaDom.reset.foreach {
          case r: FPGAResetPort =>
            props += "CONFIG.RESET_BOARD_INTERFACE" -> r.portName
          case _ => // Ignore other reset types for now
        }

      case _ =>
        throw XilinxDesignException(s"DDR4 must be instantiated in an FPGAClockDomain")
    }

    props.toMap ++ outFreqs()
  }

  /**
   * Emit the TCL commands to connect this component in the design
   */
  override def connectTclCommands: Seq[String] =
    for {
      (opt, idx) <- Seq(addnClkOut1, addnClkOut2, addnClkOut3, addnClkOut4).zipWithIndex
      cd <- opt.toList
      port <- cd.receiverPorts.flatMap(_._2())
      clkoutIdx = idx + 1
    } yield s"connect_bd_net [get_bd_pins ${this.instanceName}/${clkOut(clkoutIdx)}] [get_bd_pins $port]"
}


object DDR4 {
  private def clkOut(idx: Int): String = s"addn_ui_clkout$idx"
}