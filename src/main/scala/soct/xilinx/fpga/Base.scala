package soct.xilinx.fpga

import org.chipsalliance.cde.config.{Config, Field}
import soct.xilinx.components.{Component, IsXilinxIP}


abstract class FPGA extends Component with IsXilinxIP
{

  val hasDDR4: Boolean = false

  override def toString: String = friendlyName

}