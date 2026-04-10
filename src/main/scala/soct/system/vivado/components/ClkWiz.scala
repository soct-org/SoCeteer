package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands}
import soct.system.vivado.abstracts.BdPinPort
import soct.system.vivado.abstracts.{HasIndexedPins, _}
import soct.system.vivado.fpga.FPGAResetPortSource

import scala.collection.mutable


/**
 * Case class representing a Xilinx Clocking Wizard IP core in the block design.
 * For now, the ClkWiz IP can only be driven by a single clock input, but can provide multiple clock outputs.
 * Documentation: https://docs.amd.com/r/en-US/pg065-clk-wiz
 */
case class ClkWiz()(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip with HasIndexedPins {

  override def partName: String = "xilinx.com:ip:clk_wiz:6.0"

  object RESET extends BdPinIn("reset", ClkWiz.this)

  object LOCKED extends BdPinOut("locked", ClkWiz.this)

  case class CLK_OUT_I(idx: Int, dom: ClockDomain) extends BdPinOut(s"clk_out$idx", ClkWiz.this)
  // TODO upper limit on number of clkouts based on FPGA family
  object CLK_OUT extends IndexedPinFactory[CLK_OUT_I, ClockDomain](
    indexRange = (1, 42),
    pinConstructor = (idx, dom) => CLK_OUT_I(idx, dom)
  )

  case class CLK_IN_I(idx: Int) extends BdPinIn(s"clk_in$idx", ClkWiz.this)
  object CLK_IN extends SimpleIndexedPinFactory[CLK_IN_I](
    indexRange = (1, 1),
    pinConstructor = idx => CLK_IN_I(idx)
  )


  override def defaultProperties: Map[String, String] = {
    val m = mutable.Map.empty[String, String]
    val clkouts = CLK_OUT.all
    clkouts.foreach {
      case (idx, clkout) =>
        m += s"CONFIG.CLKOUT${idx}_REQUESTED_OUT_FREQ" -> clkout.dom.freqMHz.toInt.toString // braces are added automatically
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

  /**
   * Generate the TCL commands needed to find the specified clock output and period objects. It is of form
   *
   * set pinVar [get_pins -quiet -hier *thisInst/clk_outidx]
   *
   * set pinVar_clk [get_clocks -of_objects $pinVar]
   *
   * set pinVar_period [get_property -min PERIOD $pinVar_clock]
   *
   *
   * @param idx the index of the clock output to find (Must exist in the block design)
   * @param pinVar the name of the variable to store the pin object in
   * @return a tuple of (TCL commands, clock variable name, period variable name)
   */
  def timingTcl(idx: Int, pinVar: String): (TCLCommands, String, String) = {
    val clockVar = s"${pinVar}_clk"
    val clockCmd = s"set $clockVar [get_clocks -of_objects $$$pinVar]".tcl
    val periodVar = s"${pinVar}_period"
    val periodCmd = s"set $periodVar [get_property -min PERIOD $$$clockVar]".tcl
    val ifCmd =
      s"""# Timing constraints for ClkWiz output $idx
         |set $pinVar [get_pins -quiet -hier *$instanceName/clk_out$idx]
         |$clockCmd
         |$periodCmd
         |""".stripMargin.tcl
    (Seq(ifCmd), clockVar, periodVar)
  }

}

object ClkWiz {
  // Allow: clkWiz.CLK_OUT(n, ...) --> someChiselClock
  implicit val clkOutToChiselClock: ToSinkConnect[ClkWiz#CLK_OUT_I, chisel3.Clock] =
    (source: ClkWiz#CLK_OUT_I, sink: chisel3.Clock, bd: SOCTBdBuilder) =>
      bd.addEdge(source, BdPinPort.portToBdPin(sink)(bd))
}