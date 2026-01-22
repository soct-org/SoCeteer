package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}
import soct.system.vivado.fpga.{FPGAClockDomain, FPGAReset}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * Trait indicating that a component provides a clock signal
 */
trait ProvidesClock

/**
 * Case class representing a Xilinx Clocking Wizard IP core in the block design.
 * For now, the ClkWiz IP can only be driven by a single clock input, but can provide multiple clock outputs.
 * @param cds The output clock domains
 * @param dom The input clock domain - for example from an FPGAClockDomain or driven by DDR4
 */
case class ClkWiz(cds: Seq[ClockDomain])(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain] = None) // Clock is connected externally
  extends InstantiableBdComp with IsXilinxIP with ProvidesClock {

  override def clockInPorts: Seq[String] = Seq(s"$instanceName/${ClkWiz.CLKIn}")

  override def defaultProperties: Map[String, String] = {
    val m = mutable.Map.empty[String, String]
    val nCds = cds.length
    cds.zipWithIndex.foreach {
      case (cd, idx) =>
        val clkoutIdx = idx + 1 // clkout indices are 1-based
        m += s"CONFIG.CLKOUT${clkoutIdx}_REQUESTED_OUT_FREQ" -> cd.tclVarName.getOrElse(cd.freqMHz.toInt.toString)
        m += s"CONFIG.CLKOUT${clkoutIdx}_USED" -> "true"
    }

    m += "CONFIG.NUM_OUT_CLKS" -> nCds.toString
    if (dom.isDefined && dom.get.reset.isDefined && dom.get.reset.get.isInstanceOf[FPGAReset])
      m += "CONFIG.RESET_BOARD_INTERFACE" -> dom.get.reset.get.name

    // Enable board flow by default
    m += "CONFIG.USE_BOARD_FLOW" -> "true"

    m.toMap
  }

  override def connectTclCommands: Seq[String] = {
    for {
      (opt, idx) <- cds.zipWithIndex
      port <- opt.clkReceiverPorts.flatMap(_._2())
      clkoutIdx = idx + 1
    } yield s"connect_bd_net [get_bd_pins ${this.instanceName}/clk_out$clkoutIdx] [get_bd_pins $port]"
  }

  override def partName: String = "xilinx.com:ip:clk_wiz:6.0"
}

object ClkWiz {
  val CLKIn = "clk_in1" // Standard name for the input clock port on the clk_wiz IP
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
case class ClockDomain(name: String,
                       freqMHz: Double,
                       reset: Option[Reset] = None,
                       tclVarName: Option[String] = None) {
  if (tclVarName.isDefined) {
    SOCTBdBuilder.addBdVar(tclVarName.get, "The core clock frequency in MHz", freqMHz.toString)
  }

  // On which ports the receivers want to connect to this clock - keyed by component.
  // The ports must be fully qualified names in the block design, usually of form <instance>/<port>
  protected[components] val clkReceiverPorts = mutable.Map.empty[BdComp, () => Seq[String]]

  /**
   * Register a component as a receiver of this clock
   *
   * @param comp The component to register
   * @tparam T The type of the component
   * @return The registered component
   */
  def add[T <: BdComp](comp: T, ports: () => Seq[String]): T = {
    clkReceiverPorts += (comp -> ports)
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


