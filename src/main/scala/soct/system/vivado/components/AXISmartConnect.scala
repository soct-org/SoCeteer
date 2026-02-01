package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts._

case class AXISmartConnect()(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip {

  override def partName: String = "xilinx.com:ip:smartconnect:1.0"

}

