package soct.system.vivado.fpga

import org.chipsalliance.cde.config.Parameters
import soct.SOCTFreq._
import soct.system.vivado.{SOCTBdBuilder, VivadoDesignException}
import soct.system.vivado.misc.{FPGAPMODPin, RawPMODPin}
import soct.SOCTBytes._

object VCU118 extends FPGA {
  override val friendlyName: String = "VCU118"

  override val xilinxPart: String = "xcvu9p-flga2104-2L-e"

  override val partName: String = "xilinx.com:vcu118:part0:2.0"

  override val tpe: String = "vcu118"

  override val getPMODPorts: Seq[Int] = Seq(0, 1)

  override def intDDR4Ports: Seq[DDR4PortParams] = {
    val cap = 2.GiB // But actually 512.MB * 5 - is not a multiple of two and probably wont work with alignment
    Seq(
      new DDR4PortParams {
        override val portName: String = "ddr4_sdram_c1"
        override def getCap: Bytes = cap
      },
      new DDR4PortParams {
        override val portName: String = "ddr4_sdram_c2"
        override def getCap: Bytes = cap
      }
    )
  }

  override def uartPorts: Seq[UARTPortParams] = Seq(
    new UARTPortParams {
      override val portName: String = "rs232_uart"
    }
  )


  def initNClockPorts(n: Int)(implicit bd: SOCTBdBuilder, p: Parameters): Seq[FPGAClockDomain] = {
    lazy val reset = FPGAResetPort("reset")
    lazy val dom1: FPGAClockDomain = FPGAClockDomain(clk1, reset, 250.MHz)
    lazy val dom2: FPGAClockDomain = FPGAClockDomain(clk2, reset, 250.MHz)

    lazy val clk1 = FPGADiffClockPort("default_250mhz_clk1", () => dom1)
    lazy val clk2 = FPGADiffClockPort("default_250mhz_clk2", () => dom2)
    n match {
      case 1 =>
        Seq(dom1)
      case 2 =>
        Seq(dom1, dom2)
      case _ =>
        throw VivadoDesignException(s"VCU118 only supports 1 or 2 clock ports, but $n were requested.")
    }
  }

  override def pmod(pmodPort: Int, pmodPin: RawPMODPin): FPGAPMODPin = {
    val pin = pmodPin.pin

    val port = pmodPort match {
      case 0 => IndexedSeq(
        ("AY14", "LVCMOS18"),
        ("AY15", "LVCMOS18"),
        ("AW15", "LVCMOS18"),
        ("AV15", "LVCMOS18"),
        ("AV16", "LVCMOS18"),
        ("AU16", "LVCMOS18"),
        ("AT15", "LVCMOS18"),
        ("AT16", "LVCMOS18")
      )

      case 1 => IndexedSeq(
        ("N28", "LVCMOS12"),
        ("M30", "LVCMOS12"),
        ("N30", "LVCMOS12"),
        ("P30", "LVCMOS12"),
        ("P29", "LVCMOS12"),
        ("L31", "LVCMOS12"),
        ("M31", "LVCMOS12"),
        ("R29", "LVCMOS12")
      )

      case _ =>
        throw VivadoDesignException(
          s"Invalid PMOD port $pmodPort. VCU118 only has PMOD ports 0 and 1."
        )
    }

    if (pin < 0 || pin >= port.length) {
      throw VivadoDesignException(
        s"Invalid PMOD pin $pin. VCU118 PMOD ports expose pins 0 to 7."
      )
    }

    val (packagePin, ioStandard) = port(pin)
    FPGAPMODPin(packagePin, ioStandard, pin)
  }
}