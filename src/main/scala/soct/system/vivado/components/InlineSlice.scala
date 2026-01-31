package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, TCLCommands}
import soct.system.vivado.abstracts.{BdComp, BdPin, BdPinPort, ClockDomain, HasSingleSink, HasSingleSource, HasSinkPins, KeyForSink, SourceForSinks, XInlineHDL}
import soct.system.vivado.components.InlineSlice.{inPort, outPort}


/**
 * Inline Slice IP core from Xilinx.
 *
 * @param dinWidth  The width of the input data bus
 * @param dinFrom   The starting bit index (0-based) of the slice from the input data bus to be extracted
 * @param dinTo     The ending bit index (0-based) of the slice from the input data bus to be extracted
 * @param doutWidth The width of the output data bus
 */
case class InlineSlice(dinWidth: Int, dinFrom: Int, dinTo: Int, doutWidth: Int)
                      (implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain] = None) // Clock not needed
  extends BdComp with XInlineHDL with SourceForSinks with HasSingleSource with HasSinkPins with HasSingleSink {

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.DIN_WIDTH" -> s"$dinWidth",
    "CONFIG.DIN_FROM" -> s"$dinFrom",
    "CONFIG.DIN_TO" -> s"$dinTo",
    "CONFIG.DOUT_WIDTH" -> s"$doutWidth"
  )

  protected override def connectToSinksImpl: TCLCommands = {
    BdPinPort.connect(getSource, sinkPins)
  }

  override def partName: String = "xilinx.com:inline_hdl:ilslice:1.0"

  override def getSource: BdPinPort = BdPin(outPort, this)

  override def getSink: BdPinPort = BdPin(inPort, this)

  override protected def getPinImpl(source: SourceForSinks, sinkKey: KeyForSink): Option[BdPinPort] = {
    Some(getSink)
  }
}

object InlineSlice {
  val outPort = "Dout"
  val inPort = "Din"
}