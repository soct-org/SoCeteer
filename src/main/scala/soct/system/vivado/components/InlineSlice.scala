package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts._


/**
 * Inline Slice IP core from Xilinx.
 *
 * @param dinWidth  The width of the input data bus
 * @param dinFrom   The starting bit index (0-based) of the slice from the input data bus to be extracted
 * @param dinTo     The ending bit index (0-based) of the slice from the input data bus to be extracted
 * @param doutWidth The width of the output data bus
 */
case class InlineSlice(dinWidth: Int, dinFrom: Int, dinTo: Int, doutWidth: Int)
                      (implicit bd: SOCTBdBuilder, p: Parameters) // Clock not needed
  extends BdComp with XInlineHDL with ConnectOps {

  override def partName: String = "xilinx.com:inline_hdl:ilslice:1.0"

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.DIN_WIDTH" -> s"$dinWidth",
    "CONFIG.DIN_FROM" -> s"$dinFrom",
    "CONFIG.DIN_TO" -> s"$dinTo",
    "CONFIG.DOUT_WIDTH" -> s"$doutWidth"
  )

  object DOUT extends BdPinOut("Dout", this)

  object DIN extends BdPinIn("Din", this)

}

object InlineSlice {
  implicit val doutIsDefaultSrcBdPinPort: ToSinkConnect[InlineSlice, BdPinPort] = (comp: InlineSlice, sink: BdPinPort, bd: SOCTBdBuilder) =>
    bd.addEdge(comp.DOUT, sink)

  implicit val dinIsDefaultSinkBdPinPort: ToSinkConnect[BdPinPort, InlineSlice] = (source: BdPinPort, comp: InlineSlice, bd: SOCTBdBuilder) =>
    bd.addEdge(source, comp.DIN)
}