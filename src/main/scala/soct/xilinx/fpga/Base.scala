package soct.xilinx.fpga

import soct.xilinx.components.{Component, IsXilinxIP}


abstract class FPGA extends Component with IsXilinxIP
{

  val portsDDR4: Seq[Int] = Seq.empty

  val portsPMOD: Seq[Int] = Seq.empty

  override def toString: String = friendlyName

}