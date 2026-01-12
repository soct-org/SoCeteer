package soct.xilinx.fpga

import soct.xilinx.components.{BdComp, HasFriendlyName, IsXilinxIP}


abstract case class FPGA() extends IsXilinxIP with HasFriendlyName
{
  val xilinxPart: String

  val portsDDR4: Seq[Int] = Seq.empty

  val portsPMOD: Seq[Int] = Seq.empty

  override def toString: String = friendlyName

}