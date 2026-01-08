package soct.xilinx.fpga

import org.chipsalliance.cde.config

object ZCU104 extends FPGA {

  override val friendlyName: String = "ZCU104"

  override val partName: String = "xilinx.com:zcu104:part0:1.1"

  override val hasDDR4: Boolean = true

  override val hasPMOD: Boolean = true
}