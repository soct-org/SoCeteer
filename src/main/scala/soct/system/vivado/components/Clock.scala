package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.fpga.FPGAClockDomain

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * Trait indicating that a component provides a clock signal
 */
trait ProvidesClock

/**
 *
 * @param cds
 * @param bd
 * @param p
 * @param dom
 */
case class ClkWiz(cds: Seq[ClockDomain])(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain] = None) // Clock is connected externally
  extends InstantiableBdComp with IsXilinxIP with ProvidesClock {
  var m = mutable.Map.empty[String, String]

  override def defaultProperties: Map[String, String] = {
    val nCds = cds.length
    cds.zipWithIndex.foreach {
      case (cd, idx) =>
        val clkoutIdx = idx + 1 // clkout indices are 1-based
        m += s"CONFIG.CLKOUT${clkoutIdx}_REQUESTED_OUT_FREQ" -> cd.tclVarName.getOrElse(cd.freqMHz.toInt.toString)
        m += s"CONFIG.CLKOUT${clkoutIdx}_USED" -> "true"
    }

    m += "CONFIG.NUM_OUT_CLKS" -> nCds.toString
    if (dom.isDefined && dom.get.isInstanceOf[FPGAClockDomain] && dom.get.reset.isDefined) {
      m += "CONFIG.RESET_BOARD_INTERFACE" -> dom.get.reset.get.name
    }

    // Enable board flow by default
    m += "CONFIG.USE_BOARD_FLOW" -> "true"

    m.toMap
  }

  override def connectTclCommands: Seq[String] = {
    Seq.empty
  }


  override def partName: String = "xilinx.com:ip:clk_wiz:6.0"
}

/**
 * Case class representing a reset signal in the design
 *
 * @param name The name of the reset signal
 */
case class Reset(name: String)


/**
 * Case class representing a clock domain in the design
 *
 * @param name       The name of the clock domain
 * @param freqMHz    The frequency of the clock domain in MHz
 * @param reset      Optional reset provider that is synced to this clock domain
 * @param tclVarName Optional name of the dereferenced TCL variable representing this clock domain in the block design, e.g, "$clock_freq"
 */
case class ClockDomain(name: String, freqMHz: Double, reset: Option[Reset] = None, tclVarName: Option[String] = None)
                      (implicit bd: Option[SOCTBdBuilder] = None) {

  // If the bd builder is defined, register this clock domain with it
  bd.foreach{ bd =>
    if (tclVarName.isDefined){
      bd.addBdVar(tclVarName.get, "The core clock frequency in MHz", freqMHz.toString)
    }
  }



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


