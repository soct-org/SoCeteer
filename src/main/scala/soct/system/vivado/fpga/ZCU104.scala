package soct.system.vivado.fpga

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}
import soct.system.vivado.abstracts._


class ZCU104(implicit bd: SOCTBdBuilder, p: Parameters) extends FPGA {
  override val friendlyName: String = "ZCU104"

  override val xilinxPart: String = "xczu7ev-ffvc1156-2-e"

  override val partName: String = "xilinx.com:zcu104:part0:1.1"

  override val tpe: String = "zcu104"

  override val getPMODPorts: Seq[Int] = Seq(0, 1) // PMOD ports 0 and 1 are available, 2 is I2C

  override def initDDR4Port(i: Int): DDR4Port =
    DDR4Port("ddr4_sdram") // ZCU104 has a single DDR4 port

  override def initUARTPort(i: Int): UARTPort =
    UARTPort("uart2_pl") // ZCU104 has a single UART port

  private val clk300: FPGAClockPort = FPGAClockPort("clk_300mhz", () => clk300Dom)
  private val clk300Dom: FPGAClockDomain = FPGAClockDomain(clk300, defaultReset, 300.0)

  override def pmod(pmodPort: Int, pmodPin: RawPMODPin): FPGAPMODPin = {
    val pin = pmodPin.pin
    val port = pmodPort match {
      case 0 =>
        IndexedSeq(
          ("G8", "LVCMOS33"),
          ("H8", "LVCMOS33"),
          ("G7", "LVCMOS33"),
          ("H7", "LVCMOS33"),
          ("G6", "LVCMOS33"),
          ("H6", "LVCMOS33"),
          ("J6", "LVCMOS33"),
          ("J7", "LVCMOS33")
        )
      case 1 =>
        IndexedSeq(
          ("J9", "LVCMOS33"),
          ("K9", "LVCMOS33"),
          ("K8", "LVCMOS33"),
          ("L8", "LVCMOS33"),
          ("L10", "LVCMOS33"),
          ("M10", "LVCMOS33"),
          ("M8", "LVCMOS33"),
          ("M9", "LVCMOS33")
        )
      case _ =>
        throw XilinxDesignException(s"Invalid PMOD port: $pmodPort. ZCU104 only has PMOD ports 0 and 1 available.")
    }
    val (packagePin, ioStandard) = port(pin)
    FPGAPMODPin(packagePin, ioStandard, pin)
  }

  override val fastestClock: FPGAClockDomain = clk300Dom
}