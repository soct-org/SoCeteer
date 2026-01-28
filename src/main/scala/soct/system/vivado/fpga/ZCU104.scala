package soct.system.vivado.fpga

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.components.WithDomain


class ZCU104(implicit bd: SOCTBdBuilder, p: Parameters) extends FPGA {
  override val friendlyName: String = "ZCU104"

  override val xilinxPart: String = "xczu7ev-ffvc1156-2-e"

  override val partName: String = "xilinx.com:zcu104:part0:1.1"

  override val tpe: String = "zcu104"

  override val portsPMOD: Seq[Int] = Seq(0, 1) // PMOD ports 0 and 1 are available, 2 is I2C

  override def portsDDR4(): Seq[DDR4Port] = Seq(DDR4Port(instanceName = "ddr4_sdram"))

  private lazy val clk300: FPGAClockPort = WithDomain(clk300Dom) { implicit dom => FPGAClockPort("clk_300mhz") }
  private val clk300Dom: FPGAClockDomain = new FPGAClockDomain(300.0, defaultReset)

  override def fastestClock(): FPGAClockDomain = clk300Dom.withPort(clk300)
}