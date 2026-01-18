package soct.system.vivado.fpga

import soct.system.vivado.components.ClockDomain

object ZCU104 extends FPGA {

  override val friendlyName: String = "ZCU104"

  override val xilinxPart: String = "xczu7ev-ffvc1156-2-e"

  override val partName: String = "xilinx.com:zcu104:part0:1.1"

  override val portsDDR4: Seq[DDR4Port] = Seq(DDR4Port(
    ddr4Port = "ddr4_sdram",
    defaultReset = "reset"
  ))

  override val clocks: Seq[FPGAClockDomain] = Seq(
    new FPGAClockDomain("clk_300mhz", 300.0)
  )

  override val portsPMOD: Seq[Int] = Seq(0, 1) // PMOD ports 0 and 1 are available, 2 is I2C
}