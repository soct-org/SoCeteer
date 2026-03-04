package soct.system.vivado.misc

import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands, XilinxDesignException}
import soct.system.vivado.abstracts.{BdPinPort, EmitsConstraint}
import soct.system.vivado.fpga.FPGA


/**
 * Case class representing a raw PMOD pin that directly maps to FPGA pins without any abstraction. Valid pins are 0-7 for the signal pins.
 * This is the format that FPGA-specific PMOD pin definitions should use, and other PMOD pin representations (e.g., Digilent) should be converted to this format for FPGA pin mapping.
 */
case class RawPMODPin(pin: Int) extends BasePMODPin {
  override def toRaw: RawPMODPin = {
    if (pin >= 0 && pin <= 7) {
      this // Raw PMOD pins are already in the correct format
    } else {
      throw XilinxDesignException(s"Invalid Raw PMOD pin: $pin. Valid pins are 0-7.")
    }
  }
}


/**
 * Trait for PMOD pin representations for several Vendors. This is used to abstract over the specific PMOD pin definitions for different FPGA boards.
 */
trait BasePMODPin {
  val pin: Int

  def toRaw: RawPMODPin = throw XilinxDesignException(s"Cannot convert $this PMOD pin to a raw PMOD pin.")

  def toFPGA(pmodPort: Int, fpga: FPGA): FPGAPMODPin = {
    val rawPin = toRaw
    fpga.pmod(pmodPort, rawPin)
  }
}


/**
 * Trait for Digilent PMOD pin representations. In the doc, they include the pins for ground and power columnwise, compare
 * https://digilent.com/reference/pmod/pmodsd/reference-manual
 */
case class DigilentPMODPin(pin: Int) extends BasePMODPin {
  /**
   * Convert this Digilent PMOD pin representation to a raw PMOD pin representation that directly maps to FPGA pins without any abstraction.
   * Digilent PMOD pins start from 1 and go up to 12 (8 signal pins + 4 power/ground pins), while raw PMOD pins start from 0 and go up to 7 for the signal pins.
   * 1 - 4 are the signal pins that map to raw PMOD pins 0 - 3,
   * 5, 6 are the power pins that do not map to any raw PMOD pin and throw an exception if attempted to be converted
   * 7 - 10 are the signal pins that map to raw PMOD pins 4 - 7
   * 11, 12 are the ground pins that do not map to any raw PMOD pin and throw an exception if attempted to be converted
   *
   * @return The corresponding RawPMODPin for this Digilent PMOD pin
   * @throws XilinxDesignException if this Digilent PMOD pin does not correspond to a valid raw PMOD pin (i.e., if it is a power or ground pin or if it is out of range)
   */
  override def toRaw: RawPMODPin = {
    pin match {
      case 1 | 2 | 3 | 4 => RawPMODPin(pin - 1) // Map signal pins 1-4 to raw pins 0-3
      case 5 | 6 => throw XilinxDesignException(s"Digilent PMOD pin $pin is a power pin and does not correspond to a raw PMOD pin.")
      case 7 | 8 | 9 | 10 => RawPMODPin(pin - 3) // Map signal pins 7-10 to raw pins 4-7
      case 11 | 12 => throw XilinxDesignException(s"Digilent PMOD pin $pin is a ground pin and does not correspond to a raw PMOD pin.")
      case _ => throw XilinxDesignException(s"Invalid Digilent PMOD pin: $pin. Valid pins are 1-12.")
    }
  }
}


/**
 * Case class representing a PMOD pin on the FPGA board, including its package pin name and I/O standard.
 *
 * @param packagePin The name of the package pin corresponding to this PMOD pin (e.g., "G8", "H8", etc.)
 * @param ioStandard The I/O standard for this PMOD pin (e.g., "LVCMOS33")
 * @param pin        The raw PMOD pin index (0-7) corresponding to this PMOD pin
 */
case class FPGAPMODPin(packagePin: String, ioStandard: String, pin: Int) extends BasePMODPin {
  override def toRaw: RawPMODPin = RawPMODPin(pin)

  override def toFPGA(pmodPort: Int, fpga: FPGA): FPGAPMODPin = {
    // This pin is already in FPGA format, so just return it
    this
  }
}

trait WantsPMODPins extends EmitsConstraint {
  this: BdPinPort =>

  private def toProperty(packagePin: String, ioStandard: String, indexOpt: Option[Int]): TCLCommands = {
    val portRef = indexOpt match {
      case Some(i) => s"${this.ref}[$i]"
      case None => this.ref
    }
    Seq(
      s"set_property PACKAGE_PIN $packagePin [get_ports $portRef]".tcl,
      s"set_property IOSTANDARD $ioStandard [get_ports $portRef]".tcl
    )
  }


  override def xdcCommands()(implicit bd: SOCTBdBuilder): TCLCommands = {
    val n = pmodPins.size
    pmodPins.zipWithIndex.flatMap {
      case (p: BasePMODPin, i) =>
        val fpgaPmod = p.toFPGA(pmodPort, bd.fpgaInstance())
        toProperty(fpgaPmod.packagePin, fpgaPmod.ioStandard, if (n > 1) Some(i) else None)
    }
  }

  /**
   * The PMOD port number to which this component should be connected in the block design.
   *
   * @return The PMOD port number (e.g., 0, 1, 2) to connect to this component in the block design.
   */
  def pmodPort: Int


  /**
   * The PMOD pins corresponding to the PMOD port this component maps to.
   *
   * @return A sequence of PmodPin objects representing the pins of the PMOD port this component maps to.
   */
  def pmodPins: Seq[BasePMODPin]
}

