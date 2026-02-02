package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, TCLCommands}
import soct.system.vivado.components.ClkWiz._
import soct.system.vivado.fpga.FPGAResetPortSource
import soct.system.vivado.abstracts._


import scala.collection.mutable


/**
 * Case class representing a Xilinx Clocking Wizard IP core in the block design.
 * For now, the ClkWiz IP can only be driven by a single clock input, but can provide multiple clock outputs.
 * Documentation: https://docs.amd.com/r/en-US/pg065-clk-wiz
 */
case class ClkWiz()(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip {

  override def partName: String = "xilinx.com:ip:clk_wiz:6.0"

  object CLK_IN1 extends BdPinIn("clk_in1", ClkWiz.this)

  object RESET extends BdPinIn("reset", ClkWiz.this)

  object LOCKED extends BdPinOut("locked", ClkWiz.this)

  private val clkouts: mutable.Map[Int, CLK_OUT_I] = mutable.Map.empty
  case class CLK_OUT_I(idx: Int, dom: ClockDomain) extends BdPinOut(s"clk_out$idx", ClkWiz.this)
  def CLK_OUT(idx: Int, dom: ClockDomain): CLK_OUT_I = {
    // TODO upper limit on number of clkouts based on FPGA family
    require(idx >= 1, s"ClkWiz CLK_OUT index must be >= 1, got $idx")
    clkouts.getOrElseUpdate(idx, CLK_OUT_I(idx, dom))
  }


  override def defaultProperties: Map[String, String] = {
    val m = mutable.Map.empty[String, String]
    clkouts.foreach {
      case (idx, clkout) =>
        m += s"CONFIG.CLKOUT${idx}_REQUESTED_OUT_FREQ" -> clkout.dom.tclVarName.getOrElse(clkout.dom.freqMHz.toInt.toString)
        m += s"CONFIG.CLKOUT${idx}_USED" -> "true"
    }
    m += "CONFIG.NUM_OUT_CLKS" -> clkouts.size.toString
    m += "CONFIG.USE_BOARD_FLOW" -> "true"

    bd.sourceOf(RESET) match {
      case Some(r: FPGAResetPortSource) =>
        m += "CONFIG.RESET_BOARD_INTERFACE" -> r.instanceName
      case _ =>
    }

    m.toMap
  }
}

object ClkWiz {
}