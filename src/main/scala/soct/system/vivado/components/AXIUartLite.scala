package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands, XilinxDesignException}
import soct.system.vivado.abstracts._
import soct.system.vivado.fpga.UARTPort
import soct.system.vivado.misc.DTSInfo


/**
 * AXI UART Lite component for Xilinx FPGAs.
 */
case class AXIUartLite(override val dtsInfo: DTSInfo, override val getAxiMasterPin: BdIntfPin)
                      (implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip with ConnectOps with HasAxiSlave with HasDTSInfo {

  override def partName: String = "xilinx.com:ip:axi_uartlite:2.0"

  override def defaultProperties: Map[String, String] = {

    val uartIntf = bd.singleConnector(UART, p => p.isInstanceOf[UARTPort])

    Map(
      "CONFIG.C_BAUDRATE" -> "115200",
      "CONFIG.UARTLITE_BOARD_INTERFACE" -> uartIntf.ref,
      "CONFIG.USE_BOARD_FLOW" -> "true"
    )
  }

  object INTERRUPT extends BdPinOut("interrupt", AXIUartLite.this)

  object UART extends BdIntfPin("UART", AXIUartLite.this)

  object S_AXI_ACLK extends BdPinIn("s_axi_aclk", AXIUartLite.this)

  object S_AXI_ARESETN extends BdPinIn("s_axi_aresetn", AXIUartLite.this)

  override lazy val S_AXI: BdIntfPin = new BdIntfPin("S_AXI", this)

  override def assignAddrTcl: TCLCommands = {
    val regs = dtsInfo.regs
    if (regs.size != 1) {
      throw XilinxDesignException(s"AXIUartLite component requires exactly one register region in DTS info, but found ${regs.size}")
    }
    val (_, offset, size) = regs.head

    Seq(
      s"assign_bd_address -offset $offset -range $size -target_address_space [get_bd_addr_spaces ${getAxiMasterPin.ref}] [get_bd_addr_segs ${S_AXI.ref}/Reg]".tcl
    )
  }
}
