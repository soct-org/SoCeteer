package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts.{BdComp, BdPinIn, BdPinOut, ConnectOps, HasIndexedPins, XInlineHDL}


case class InlineConcat(nPorts: Int) (implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with XInlineHDL with ConnectOps with HasIndexedPins {
  override def partName: String = "xilinx.com:inline_hdl:ilconcat:1.0"

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.NUM_PORTS" -> s"$nPorts"
  )

  object DOUT extends BdPinOut("dout", InlineConcat.this)

  case class IN_I(idx: Int) extends BdPinIn(s"In$idx", InlineConcat.this)
  object IN extends SimpleIndexedPinFactory[IN_I](
    indexRange = (0, nPorts - 1),
    pinConstructor = idx => IN_I(idx)
  )
}
