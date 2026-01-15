package soct.system.vivado.fpga

import org.chipsalliance.cde.config

object ZCU104 extends FPGA {

  override val friendlyName: String = "ZCU104"

  override val xilinxPart: String = "xczu7ev-ffvc1156-2-e"

  override val partName: String = "xilinx.com:zcu104:part0:1.1"

  override val portsDDR4: Seq[Int] = Seq(0) // DDR4 port 0 is available

  override val portsPMOD: Seq[Int] = Seq(0, 1) // PMOD ports 0 and 1 are available, 2 is I2C
}