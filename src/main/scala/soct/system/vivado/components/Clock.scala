package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer


trait ProvidesClock {

}

case class ClkWiz(cds: Seq[ClockDomain])(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain] = None) // Clock is connected externally
  extends InstantiableBdComp with IsXilinxIP with ProvidesClock {


  /**
   * Remember all components that want to be connected to this clock output
   * Maps the full port name, e.g., "axi_uartlite_0/s_axi_aclk" to the component
   */
  var slavePorts = mutable.Map.empty[String, BdComp]

  override def connectTclCommands: Seq[String] = {
    Seq.empty
  }


  override def partName: String = "xilinx.com:ip:clk_wiz:6.0"
}


/**
 * Case class representing a clock domain in the design
 *
 * @param name    The name of the clock domain / port
 * @param freqMHz The frequency of the clock domain in MHz
 */
case class ClockDomain(name: String, freqMHz: Double) {

  protected[components] val clkReceivers: ArrayBuffer[BdComp] = mutable.ArrayBuffer.empty[BdComp]

  /**
   * Register a component as a receiver of this clock
   *
   * @param comp The component to register
   * @tparam T The type of the component
   * @return The registered component
   */
  def add[T <: BdComp](comp: T): T = {
    clkReceivers += comp
    comp
  }
}

object WithDomain {
  def apply[T](cd: ClockDomain)(
    block: Option[ClockDomain] => T
  ): T = {
    block(Some(cd))
  }
}


