package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, TCLCommands}
import soct.system.vivado.components.ClkWiz._
import soct.system.vivado.fpga.FPGAResetPortType
import soct.system.vivado.abstracts._


import scala.collection.mutable


/**
 * Case class representing a Xilinx Clocking Wizard IP core in the block design.
 * For now, the ClkWiz IP can only be driven by a single clock input, but can provide multiple clock outputs.
 *
 * @param domains The output clock domains
 * @param dom The input clock domain - for example from an FPGAClockDomain or driven by DDR4
 */
case class ClkWiz(override val domains: Seq[ClockDomain])(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain] = None) // Clock is connected externally
  extends BdComp with Xip with SourceForSinks with HasSinkPins with AutoConnect with ProvidesAutoClock {

  override def partName: String = "xilinx.com:ip:clk_wiz:6.0"

  override def clockInPorts: Seq[BdPinPort] = Seq(BdPin(CLKIn, this))

  override def resetInPorts: Seq[BdPinPort] = Seq(BdPin(RSTIn, this))

  override def defaultProperties: Map[String, String] = {
    val m = mutable.Map.empty[String, String]
    val nCds = domains.length
    domains.zipWithIndex.foreach {
      case (cd, idx) =>
        val clkoutIdx = idx + 1 // clkout indices are 1-based
        m += s"CONFIG.CLKOUT${clkoutIdx}_REQUESTED_OUT_FREQ" -> cd.tclVarName.getOrElse(cd.freqMHz.toInt.toString)
        m += s"CONFIG.CLKOUT${clkoutIdx}_USED" -> "true"
    }
    m += "CONFIG.NUM_OUT_CLKS" -> nCds.toString
    m += "CONFIG.USE_BOARD_FLOW" -> "true"
    dom.foreach(_.reset.foreach{
      case r: FPGAResetPortType =>
        m += "CONFIG.RESET_BOARD_INTERFACE" -> r.instanceName
      case _ => // Ignore other reset types for now
    })

    m.toMap
  }

  override protected def outPortImpl(cd: ClockDomain, domIdx: Int, sinkPin: BdPinPort, pinIdx: Int): BdPin = {
    val clkoutIdx = domIdx + 1
    // TODO validate clkoutIdx based on selected board, some have more than others
    BdPin(clkOut(clkoutIdx), this)
  }

  override protected def getPinImpl(source: SourceForSinks): Option[BdPinPort] = {
    None // For now, no specific pin mapping.
  }

  override protected def connectToSinksImpl: TCLCommands = {
    Seq.empty // For now, only has clock outputs (will change in future)
  }
}

object ClkWiz {
  private def clkOut(idx: Int): String = s"clk_out$idx"
  private val CLKIn = "clk_in1"
  private val RSTIn = "reset"
}