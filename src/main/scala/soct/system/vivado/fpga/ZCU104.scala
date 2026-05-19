package soct.system.vivado.fpga

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}
import soct.system.vivado.misc.{FPGAPMODPin, RawPMODPin}


object ZCU104 extends FPGA {
  override val friendlyName: String = "ZCU104"

  override val xilinxPart: String = "xczu7ev-ffvc1156-2-e"

  override val partName: String = "xilinx.com:zcu104:part0:1.1"

  override val tpe: String = "zcu104"

  override val getPMODPorts: Seq[Int] = Seq(0, 1) // PMOD ports 0 and 1; port 2 is I2C

  override def initDDR4Port(i: Int)(implicit bd: SOCTBdBuilder, p: Parameters): DDR4Port =
    DDR4Port("ddr4_sdram")

  override def initUARTPort(i: Int)(implicit bd: SOCTBdBuilder, p: Parameters): UARTPort =
    UARTPort("uart2_pl")

  override def initFastestClock(implicit bd: SOCTBdBuilder, p: Parameters): FPGAClockDomain = {
    // ZCU104 provides a 300 MHz differential (LVDS) clock pair
    lazy val dom: FPGAClockDomain = FPGAClockDomain(clk, FPGAResetPort("reset"), 300.0)
    lazy val clk: FPGADiffClockPort = FPGADiffClockPort("clk_300mhz", () => dom)
    dom
  }

  override def pmod(pmodPort: Int, pmodPin: RawPMODPin): FPGAPMODPin = {
    val pin = pmodPin.pin
    val port = pmodPort match {
      case 0 => IndexedSeq(
        ("G8", "LVCMOS33"), ("H8", "LVCMOS33"), ("G7", "LVCMOS33"), ("H7", "LVCMOS33"),
        ("G6", "LVCMOS33"), ("H6", "LVCMOS33"), ("J6", "LVCMOS33"), ("J7", "LVCMOS33")
      )
      case 1 => IndexedSeq(
        ("J9", "LVCMOS33"), ("K9", "LVCMOS33"), ("K8", "LVCMOS33"), ("L8", "LVCMOS33"),
        ("L10", "LVCMOS33"), ("M10", "LVCMOS33"), ("M8", "LVCMOS33"), ("M9", "LVCMOS33")
      )
      case _ =>
        throw XilinxDesignException(s"Invalid PMOD port $pmodPort. ZCU104 only has PMOD ports 0 and 1.")
    }
    val (packagePin, ioStandard) = port(pin)
    FPGAPMODPin(packagePin, ioStandard, pin)
  }
}