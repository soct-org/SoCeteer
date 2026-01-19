package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer


trait ProvidesClock {

}

case class ClkWiz(cds: Seq[ClockDomain])(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain] = None) // Clock is connected externally
  extends InstantiableBdComp with IsXilinxIP with ProvidesClock {

  override def connectTclCommands: Seq[String] = {
    Seq.empty
  }


  override def partName: String = "xilinx.com:ip:clk_wiz:6.0"
}

/**
 * Case class representing a reset signal in the design
 * @param name The name of the reset signal
 */
case class Reset(name: String)


/**
 * Case class representing a clock domain in the design
 *
 * @param name    The name of the clock domain
 * @param freqMHz The frequency of the clock domain in MHz
 * @param reset   Optional reset provider that is synced to this clock domain
 */
case class ClockDomain(name: String, freqMHz: Double, reset: Option[Reset] = None) {

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


