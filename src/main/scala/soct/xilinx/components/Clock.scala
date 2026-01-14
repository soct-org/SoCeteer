package soct.xilinx.components

import org.chipsalliance.cde.config.Parameters
import soct.ChiselTop
import soct.xilinx.BDBuilder

case class DiffClockBdIntfPort(freqMhz: Double) (implicit bd: BDBuilder, p: Parameters) extends XilinxBdIntfPort {
  override def INTERFACE_NAME: String = {
    s"diff_clock_${freqMhz.toInt}mhz"
  }

  override def mode: String = "Slave"

  override def partName: String = "xilinx.com:interface:diff_clock_rtl:1.0"
}
