package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}
import soct.system.vivado.fpga.{FPGAClockDomain, FPGAReset}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * Case class representing a Xilinx Clocking Wizard IP core in the block design.
 * For now, the ClkWiz IP can only be driven by a single clock input, but can provide multiple clock outputs.
 *
 * @param cds The output clock domains
 * @param dom The input clock domain - for example from an FPGAClockDomain or driven by DDR4
 */
case class ClkWiz(cds: Seq[ClockDomain])(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain] = None) // Clock is connected externally
  extends InstantiableBdComp with IsXilinxIP  {

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

    // Enable board flow by default
    m += "CONFIG.USE_BOARD_FLOW" -> "true"

    if (dom.isDefined && dom.get.reset.isDefined && dom.get.reset.get.isInstanceOf[FPGAReset])
      m += "CONFIG.RESET_BOARD_INTERFACE" -> dom.get.reset.get.ref

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
 * Marker trait for reset providers
 */
trait Reset {
  /**
   * The reference name of the reset provider in the block design - e.g., instance/port or a board port
   */
  def ref: String
}


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
                       tclVarName: Option[String] = None)
                      (implicit bd: SOCTBdBuilder) {
  if (tclVarName.isDefined) {
    bd.addBdVar(tclVarName.get, "The core clock frequency in MHz", freqMHz.toString)
  }

  // On which ports the receivers want to connect to this clock - keyed by component.
  // The ports must be fully qualified names in the block design, usually of form <instance>/<port>
  protected[components] val clkReceiverPorts = mutable.Map.empty[BdComp, () => Seq[String]]

  /**
   * Register a component as a receiver of this clock
   *
   * @param comp  The component to register
   * @param ports Function returning the list of ports on the component to connect to this clock
   * @tparam T The type of the component
   * @return The registered component
   */
  def add[T <: BdComp](comp: T, ports: () => Seq[String]): T = {
    clkReceiverPorts += (comp -> ports)
    comp
  }
}
/*
  connect_bd_net -net reset_1  [get_bd_ports reset] \
  [get_bd_pins clk_wiz_0/reset] \
  [get_bd_pins rst_clk_wiz_0_100M/ext_reset_in] \
  [get_bd_pins ddr4_0/sys_rst]
  connect_bd_net -net rst_clk_wiz_0_100M_peripheral_aresetn  [get_bd_pins rst_clk_wiz_0_100M/peripheral_aresetn] \
  [get_bd_pins axi_smc/aresetn] \
  [get_bd_pins axi_uartlite_0/s_axi_aresetn] \
  [get_bd_pins smartconnect_0/aresetn] \
  [get_bd_pins smartconnect_1/aresetn] \
  [get_bd_pins sdc_controller_0/async_resetn] \
  [get_bd_pins util_vector_logic_0/Op1]
 */

/**
 * Helper object to instantiate components within a given clock domain
 */
object WithDomain {

  /**
   * Instantiate a block within the given clock domain
   *
   * @param cd    The clock domain
   * @param block The block to instantiate
   * @tparam T The return type of the block
   * @return The result of the block
   */
  def apply[T, C <: ClockDomain](cd: C)(
    block: Option[C] => T
  ): T = {
    block(Some(cd))
  }
}


