package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts._

import scala.collection.mutable

case class AXISmartConnect()(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip {

  override def partName: String = "xilinx.com:ip:smartconnect:1.0"

  object ARESETN extends BdPinIn("aresetn", AXISmartConnect.this)

  private val saxis: mutable.Map[Int, S_AXI_I] = mutable.Map.empty
  case class S_AXI_I(idx: Int) extends BdIntfPin(s"S" + f"$idx%02d" + "_AXI", AXISmartConnect.this)
  def S_AXI(idx: Int): S_AXI_I = {
    require(idx >= 0 && idx <= 15, s"AXISmartConnect S_AXI index must be between 0 and 15, got $idx")
    saxis.getOrElseUpdate(idx, S_AXI_I(idx))
  }

  private val maxis: mutable.Map[Int, M_AXI_I] = mutable.Map.empty
  case class M_AXI_I(idx: Int) extends BdIntfPin(s"M" + f"$idx%02d" + "_AXI", AXISmartConnect.this)
  def M_AXI(idx: Int): M_AXI_I = {
    require(idx >= 0 && idx <= 15, s"AXISmartConnect M_AXI index must be between 0 and 15, got $idx")
    maxis.getOrElseUpdate(idx, M_AXI_I(idx))
  }

  private val aclks: mutable.Map[Int, ACLK_I] = mutable.Map.empty
  case class ACLK_I(idx: Int) extends BdPinIn(s"aclk" + f"$idx%02d", AXISmartConnect.this)
  def ACLK(idx: Int): ACLK_I = {
    require(idx >= 0, s"AXISmartConnect ACLK index must be >= 0, got $idx") // TODO upper limit?
    aclks.getOrElseUpdate(idx, ACLK_I(idx))
  }

  override def defaultProperties: Map[String, String] = {
    val mI = maxis.size max 1
    val sI = saxis.size max 1
    Map(
      "CONFIG.NUM_MI" -> mI.toString,
      "CONFIG.NUM_SI" -> sI.toString
    )
  }
}

