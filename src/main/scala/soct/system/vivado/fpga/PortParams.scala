package soct.system.vivado.fpga

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts.{BdIntfPort, PortMode}

/**
 * A board interface that can be instantiated as a master interface port in the block design
 * (e.g. a DDR4 SODIMM socket or a UART header).
 */
trait IsMasterIf {
  /**
   * The name of the port in the block design
   */
  val portName: String

  /**
   * The Xilinx interface type for this port in the block design.
   */
  def partName: String


  /**
   * Instantiates the master interface port for this board interface.
   *
   * @return the port
   */
  def initPort(implicit bd: SOCTBdBuilder, p: Parameters): BdIntfPort =
    BdIntfPort(portName, partName, PortMode.Master)
}


/**
 * Trait representing the parameters of a UART port on the FPGA board.
 */
trait UARTPortParams extends IsMasterIf {
  override def partName: String = "xilinx.com:interface:uart_rtl:1.0"
}
