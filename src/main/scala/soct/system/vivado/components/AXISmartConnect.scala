package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts._

case class AXISmartConnect()(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip with HasIndexedPins {

  override def partName: String = "xilinx.com:ip:smartconnect:1.0"

  object ARESETN extends BdPinIn("aresetn", AXISmartConnect.this)

  case class S_AXI_I(idx: Int) extends BdIntfPin(s"S" + f"$idx%02d" + "_AXI", AXISmartConnect.this)
  object S_AXI extends SimpleIndexedPinFactory[S_AXI_I](
    indexRange = (0, 15),
    pinConstructor = idx => S_AXI_I(idx)
  )

  case class M_AXI_I(idx: Int) extends BdIntfPin(s"M" + f"$idx%02d" + "_AXI", AXISmartConnect.this)
  object M_AXI extends SimpleIndexedPinFactory[M_AXI_I](
    indexRange = (0, 15),
    pinConstructor = idx => M_AXI_I(idx)
  )

  def axiIdxSuffix(idx: Int): String = idx match {
    case 0 => "" // No prefix for index 0
    case _ => f"$idx%02d" // Two-digit index with leading zeros
  }

  case class ACLK_I(idx: Int) extends BdPinIn(s"aclk" + axiIdxSuffix(idx), AXISmartConnect.this)
  object ACLK extends SimpleIndexedPinFactory[ACLK_I](
    indexRange = (0, Int.MaxValue), // TODO upper limit?
    pinConstructor = idx => ACLK_I(idx)
  )

  override def defaultProperties: Map[String, String] = {
    val mI = M_AXI.size max 1
    val sI = S_AXI.size max 1
    Map(
      "CONFIG.NUM_MI" -> mI.toString,
      "CONFIG.NUM_SI" -> sI.toString
    )
  }
}