package soct.xilinx.components

import org.chipsalliance.cde.config.Parameters
import soct.ChiselTop
import soct.xilinx.BDBuilder

case class AXISmartConnect()(implicit bd: BDBuilder, p: Parameters, top: ChiselTop)
  extends InstantiableBdComp with IsXilinxIP {


  override def partName: String = "xilinx.com:ip:smartconnect:1.0"


}

