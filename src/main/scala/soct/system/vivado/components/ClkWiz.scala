package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.components.ClkWiz._
import soct.system.vivado.fpga.FPGAResetPortType

import scala.collection.mutable


/**
 * Case class representing a Xilinx Clocking Wizard IP core in the block design.
 * For now, the ClkWiz IP can only be driven by a single clock input, but can provide multiple clock outputs.
 *
 * @param cds The output clock domains
 * @param dom The input clock domain - for example from an FPGAClockDomain or driven by DDR4
 */
case class ClkWiz(cds: Seq[ClockDomain])(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain] = None) // Clock is connected externally
  extends InstantiableBdComp with IsXilinxIP with AutoConnect {

  override def clockInPorts: Seq[String] = Seq(s"$instanceName/$CLKIn")

  override def resetInPorts: Seq[String] = Seq(s"$instanceName/$RSTIn")


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

    dom.foreach(_.reset.foreach{
      case r: FPGAResetPortType =>
        m += "CONFIG.RESET_BOARD_INTERFACE" -> r.ifName
      case _ => // Ignore other reset types for now
    })

    m.toMap
  }

  override def connectTclCommands: Seq[String] = {
    for {
      (opt, idx) <- cds.zipWithIndex
      port <- opt.receiverPorts.flatMap(_._2())
      clkoutIdx = idx + 1
    } yield s"connect_bd_net [get_bd_pins ${this.instanceName}/clk_out$clkoutIdx] [get_bd_pins $port]"
  }

  override def partName: String = "xilinx.com:ip:clk_wiz:6.0"
}

object ClkWiz {
  private val CLKIn = "clk_in1" // Standard name for the input clock port on the clk_wiz IP

  private val RSTIn = "reset"   // Standard name for the reset input port on the clk_wiz IP
}