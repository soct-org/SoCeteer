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

  override def extDDR4Ports: Seq[DDR4PortParams] = Seq(
    new DDR4PortParams {
      override val portName: String = "ddr4_sdram"

      // The ZCU104 board files preset the DDR4 SODIMM interface to this 4 GiB part and the
      // board flow locks the controller to it.
      override def defaultMemoryPart: Option[String] = Some("MTA8ATF51264HZ-2G1")
    },
  )

  override def uartPorts: Seq[UARTPortParams] = Seq(
    new UARTPortParams {
      override val portName: String = "uart2_pl"
    }
  )


  override def initNClockPorts(n: Int)(implicit bd: SOCTBdBuilder, p: Parameters): Seq[FPGAClockDomain] = {
    lazy val reset = FPGAResetPort("reset")
    lazy val dom: FPGAClockDomain = FPGAClockDomain(clk, reset, 300.0)
    lazy val clk: FPGADiffClockPort = FPGADiffClockPort("clk_300mhz", () => dom)
    n match {
      case 1 => Seq(dom)
      case _ => throw XilinxDesignException(s"ZCU104 only provides a single differential clock input. Requested $n clock domains.")
    }
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