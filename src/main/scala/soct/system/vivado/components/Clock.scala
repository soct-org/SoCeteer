package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder

import scala.collection.mutable



case class DiffClockBdIntfPort(freqMhz: Double) (implicit bd: SOCTBdBuilder, p: Parameters) extends XilinxBdIntfPort {
  override def INTERFACE_NAME: String = {
    s"diff_clock_${freqMhz.toInt}mhz"
  }

  override def mode: String = "Slave"

  override def partName: String = "xilinx.com:interface:diff_clock_rtl:1.0"
}


/**
 * Case class representing a clock domain in the design
 *
 * @param name         The name of the clock domain
 * @param frequencyMHz The frequency of the clock domain in MHz
 */
case class ClockDomain(name: String, frequencyMHz: Double)(implicit bd: SOCTBdBuilder, p: Parameters) extends BdComp




case class ClockWizClk(freqMhz: Double)
                      (implicit bd: SOCTBdBuilder, p: Parameters) extends InstantiableBdComp with IsXilinxIP {


  /**
   * Remember all components that want to be connected to this clock output
   * Maps the full port name, e.g., "axi_uartlite_0/s_axi_aclk" to the component
   */
  var slavePorts = mutable.Map.empty[String, BdComp]

  override def connectTclCommands: Seq[String] = {
    Seq.empty
  }


  override def partName: String = "xilinx.com:ip:clk_wiz:6.0"


  override def friendlyName: String = s"clk_wiz_${freqMhz.toInt}mhz"
}
