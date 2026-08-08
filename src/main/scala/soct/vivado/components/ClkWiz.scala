package soct.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.SOCTFreq.Freq
import soct.vivado.{SOCTBdBuilder, VivadoDesignException}
import soct.vivado.abstracts.{BdPinIn, HasIndexedPins, _}
import soct.vivado.fpga.{FPGADiffClockPort, FPGAResetPortSource, FPGASingleEndedClockPort}

import java.util.Locale

import scala.collection.mutable


/**
 * Case class representing a Xilinx Clocking Wizard IP core in the block design.
 * For now, the ClkWiz IP can only be driven by a single clock input, but can provide multiple clock outputs.
 * Documentation: https://docs.amd.com/r/en-US/pg065-clk-wiz
 *
 * @param inputDom    clock domain of the input clock when it is a BD-internal net (e.g. a DDR4
 *                    additional clock output) instead of a board clock port; board clock ports
 *                    carry their own frequency and must not set this
 * @param dynReconfig expose the AXI4-Lite dynamic-reconfiguration interface (PG065 chapter
 *                    "Dynamic Reconfiguration through AXI4-Lite"): software can then retune
 *                    the MMCM at runtime through [[S_AXI_LITE]]. The static configuration
 *                    stays the timing-closure configuration - runtime retuning must only
 *                    ever LOWER output frequencies, constraints are not re-derived.
 */
case class ClkWiz(inputDom: Option[ClockDomain] = None, dynReconfig: Boolean = false)
                 (implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip with HasIndexedPins {

  override def partName: String = "xilinx.com:ip:clk_wiz:6.0"

  object RESET extends BdPinIn("reset", ClkWiz.this)

  object LOCKED extends BdPinOut("locked", ClkWiz.this)

  /** The dynamic-reconfiguration register interface; only present with [[dynReconfig]]. */
  object S_AXI_LITE extends BdIntfPin("s_axi_lite", ClkWiz.this)

  object S_AXI_ACLK extends BdPinIn("s_axi_aclk", ClkWiz.this)

  object S_AXI_ARESETN extends BdPinIn("s_axi_aresetn", ClkWiz.this)

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
   * @throws soct.vivado.VivadoDesignException if both or neither of clk_in1/CLK_IN1_D
   *                                                  are driven, or the reset pin has no reset source
   */
  override def defaultProperties: Map[String, String] = {
    val m = mutable.Map.empty[String, String]
    val clkouts = CLK_OUT.all
    clkouts.foreach {
      case (idx, clkout) =>
        m += s"CONFIG.CLKOUT${idx}_REQUESTED_OUT_FREQ" -> "%.3f".formatLocal(Locale.ROOT, clkout.dom.freq.toMHz) // braces are added automatically
        m += s"CONFIG.CLKOUT${idx}_USED" -> "true"
        // Only non-default requests are emitted; the IP's own defaults (50% duty,
        // 0 degrees) stay implicit so a plain domain leaves the configuration untouched.
        if (clkout.dom.dutyCycle != 0.5)
          m += s"CONFIG.CLKOUT${idx}_REQUESTED_DUTY_CYCLE" -> "%.3f".formatLocal(Locale.ROOT, clkout.dom.dutyCycle * 100)
        if (clkout.dom.phaseDeg != 0.0)
          m += s"CONFIG.CLKOUT${idx}_REQUESTED_PHASE" -> "%.3f".formatLocal(Locale.ROOT, clkout.dom.phaseDeg)
    }
    m += "CONFIG.NUM_OUT_CLKS" -> clkouts.size.toString

    if (dynReconfig) m += "CONFIG.USE_DYN_RECONFIG" -> "true"

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
      case (None, Some(_)) if inputDom.isDefined =>
        // BD-internal clock net (e.g. a DDR4 additional clock output): no buffer, and the
        // input frequency cannot be inferred from a board file - it comes from the parameter.
        m += "CONFIG.PRIM_SOURCE" -> "No_buffer"
        m += "CONFIG.PRIM_IN_FREQ" -> "%.3f".formatLocal(Locale.ROOT, inputDom.get.freq.toMHz)
      case (None, Some(_)) =>
        throw VivadoDesignException(s"ClkWiz $instanceName clk_in1 is driven by a BD-internal net; pass inputDom so the input frequency is known.")
      case _ =>
        throw VivadoDesignException(s"ClkWiz $instanceName clk_in1 must be connected to a clock source, but it is not connected to any source.")
    }

    if (dynReconfig) {
      // With the AXI4-Lite reconfiguration interface the core has NO standalone `reset`
      // input - s_axi_aresetn is the core's reset (verified against the generated core:
      // Vivado errors "No pins matched .../reset" when it is wired).
      if (bd.sourceOf(RESET).isDefined)
        throw VivadoDesignException(s"ClkWiz $instanceName uses dynamic reconfiguration: the core has no standalone `reset` input - drive s_axi_aresetn instead.")
      if (bd.sourceOf(S_AXI_ARESETN).isEmpty)
        throw VivadoDesignException(s"ClkWiz $instanceName uses dynamic reconfiguration and needs s_axi_aresetn driven - it is the core's reset.")
    } else bd.sourceOf(RESET) match {
      case Some(r: FPGAResetPortSource) =>
        m += "CONFIG.RESET_BOARD_INTERFACE" -> r.instanceName
      case Some(_) =>
      // BD-internal reset (e.g. from a ProcSysReset): no board association needed; the IP's
      // default reset polarity (active high) matches the PSR's PeripheralReset.
      case None =>
        throw VivadoDesignException(s"ClkWiz $instanceName reset must be connected to a reset source, but it is not connected to any source.")
    }

    m.toMap
  }
}