package soct.system.vivado.fpga

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder

object ZCU104 extends FPGA {

  override val friendlyName: String = "ZCU104"

  override val xilinxPart: String = "xczu7ev-ffvc1156-2-e"

  override val partName: String = "xilinx.com:zcu104:part0:1.1"

  override def portsDDR4()(implicit bd: SOCTBdBuilder, p:Parameters): Seq[DDR4Port] =
    Seq(new DDR4Port(portName = "ddr4_sdram"))

  override def fastestClock()(implicit bd: SOCTBdBuilder, p:Parameters): FPGAClockDomain =
    new FPGAClockDomain("clk_300mhz", 300.0, Some(new FPGAReset("reset"))
  )

  override val portsPMOD: Seq[Int] = Seq(0, 1) // PMOD ports 0 and 1 are available, 2 is I2C
}