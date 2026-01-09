package soct.xilinx.fpga

import soct.xilinx.components.{BdComp, HasFriendlyName, IsXilinxIP}


abstract class FPGA() extends IsXilinxIP with HasFriendlyName
{

  val portsDDR4: Seq[Int] = Seq.empty

  val portsPMOD: Seq[Int] = Seq.empty

  override def toString: String = friendlyName

}