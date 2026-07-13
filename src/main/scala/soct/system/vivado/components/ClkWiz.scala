package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, VivadoDesignException}
import soct.system.vivado.abstracts.{BdPinIn, HasIndexedPins, _}
import soct.system.vivado.fpga.{FPGADiffClockPort, FPGAResetPortSource, FPGASingleEndedClockPort}

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

  case class CLK_IN_D_I(idx: Int) extends BdIntfPin(s"CLK_IN${idx}_D", ClkWiz.this) with DrivenByNet
  object CLK_IN_D extends SimpleIndexedPinFactory[CLK_IN_D_I](
    indexRange = (1, 1),
    pinConstructor = idx => CLK_IN_D_I(idx)
  )


  /**
   * @throws soct.system.vivado.VivadoDesignException if both or neither of clk_in1/CLK_IN1_D
   *                                                  are driven, or the reset pin has no reset source
   */
  override def defaultProperties: Map[String, String] = {
    val m = mutable.Map.empty[String, String]
    val clkouts = CLK_OUT.all
    clkouts.foreach {
      case (idx, clkout) =>
        m += s"CONFIG.CLKOUT${idx}_REQUESTED_OUT_FREQ" -> clkout.dom.freq.toMHz.toInt.toString // braces are added automatically
        m += s"CONFIG.CLKOUT${idx}_USED" -> "true"
    }
    m += "CONFIG.NUM_OUT_CLKS" -> clkouts.size.toString

    val clkIn1Src = CLK_IN.get(1).flatMap(bd.sourceOf)
    val clkIn1DSrc = CLK_IN_D.get(1).flatMap(bd.sourceOf)

    if (clkIn1Src.isDefined && clkIn1DSrc.isDefined) {
      throw VivadoDesignException(s"ClkWiz $instanceName clk_in1 and clk_in1_d cannot both be connected to a source. Only one clock input can be used.")
    }

    (clkIn1DSrc, clkIn1Src) match {
      case (Some(_: FPGADiffClockPort), None) =>
        m += "CONFIG.PRIM_SOURCE" -> "Differential_clock_capable_pin"
      case (None, Some(_: FPGASingleEndedClockPort)) =>
        m += "CONFIG.PRIM_SOURCE" -> "Global_buffer"
      case _ =>
        throw VivadoDesignException(s"ClkWiz $instanceName clk_in1 must be connected to a clock source, but it is not connected to any source.")
    }

    bd.sourceOf(RESET) match {
      case Some(r: FPGAResetPortSource) =>
        m += "CONFIG.RESET_BOARD_INTERFACE" -> r.instanceName
      case _ =>
        throw VivadoDesignException(s"ClkWiz $instanceName reset must be connected to a reset source, but it is not connected to any source.")
    }

    m.toMap
  }
}