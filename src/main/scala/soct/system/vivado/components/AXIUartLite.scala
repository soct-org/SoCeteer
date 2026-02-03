package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts.{BdComp, BdIntfPin, BdPinIn, ConnectOps, DrivesNet, Xip}
import soct.system.vivado.fpga.UARTPort


/**
 * AXI UART Lite component for Xilinx FPGAs.
 */
class AXIUartLite(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip with ConnectOps {

  override def partName: String = "xilinx.com:ip:axi_uartlite:2.0"

  override def defaultProperties: Map[String, String] = {

    val uartIntf = bd.singleConnector(UART, p => p.isInstanceOf[UARTPort])

    Map(
      "CONFIG.C_BAUDRATE" -> "115200",
      "CONFIG.UARTLITE_BOARD_INTERFACE" -> uartIntf.ref,
      "CONFIG.USE_BOARD_FLOW" -> "true"
    )
  }

  object S_AXI extends BdIntfPin("S_AXI", AXIUartLite.this)


  object UART extends BdIntfPin("UART", AXIUartLite.this)


  object S_AXI_ACKL extends BdPinIn("s_axi_aclk", AXIUartLite.this)


  object S_AXI_ARESETN extends BdPinIn("s_axi_aresetn", AXIUartLite.this)
}
