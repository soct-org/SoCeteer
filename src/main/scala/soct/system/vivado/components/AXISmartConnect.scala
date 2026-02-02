package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts._

case class AXISmartConnect()(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip {

  override def partName: String = "xilinx.com:ip:smartconnect:1.0"

  case class S_AXI_I(idx: Int) extends BdIntfPin(s"S" + f"$idx%02d" + "_AXI", AXISmartConnect.this)

  def S_AXI(idx: Int): S_AXI_I = {
    require(idx >= 0 && idx <= 15, s"AXISmartConnect S_AXI index must be between 0 and 15, got $idx")
    S_AXI_I(idx)
  }


  case class M_AXI_I(idx: Int) extends BdIntfPin(s"M" + f"$idx%02d" + "_AXI", AXISmartConnect.this)

  def M_AXI(idx: Int): M_AXI_I = {
    require(idx >= 0 && idx <= 15, s"AXISmartConnect M_AXI index must be between 0 and 15, got $idx")
    M_AXI_I(idx)
  }


  object ARESETN extends BdPinIn("aresetn", AXISmartConnect.this)

  case class ACLK_I(idx: Int) extends BdPinIn(s"aclk" + f"$idx%02d", AXISmartConnect.this)

  def ACLK(idx: Int): ACLK_I = {
    require(idx >= 0, s"AXISmartConnect ACLK index must be >= 0, got $idx") // TODO upper limit?
    ACLK_I(idx)
  }


}

