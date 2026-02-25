package soct.system.vivado.abstracts

import soct.system.vivado.fpga.FPGA
import soct.system.vivado.misc.DTSInfo
import soct.system.vivado.{TCLCommands, XilinxDesignException}

import java.nio.file.{Files, Path}


/**
 * Trait for components that have a Xilinx part name
 */
trait IsXilinx {
  /**
   * The part name of this Xilinx IP
   */
  def partName: String

  /**
   * The type of this Xilinx IP, e.g., "ip", "interface", "inline_hdl", "signal" etc.
   */
  val tpe: String
}

/**
 * Trait for Xilinx Inline HDL components
 */
trait XInlineHDL extends IsXilinx {
  override val tpe: String = "inline_hdl"
}

/**
 * Trait for Xilinx IP components
 */
trait Xip extends IsXilinx {
  override val tpe: String = "ip"
}

/**
 * Trait for Xilinx Interface components
 */
trait XIntf extends IsXilinx {
  override val tpe: String = "interface"
}


/**
 * Trait for Xilinx Signal components
 */
trait XSignal extends IsXilinx {
  override val tpe: String = "signal"
}


/**
 * Trait for custom module components
 */
trait IsModule extends HasCollaterals {
  /**
   * The reference name of this module - as defined in the collateral files
   */
  def reference: String
}


/**
 * Trait for components that have collateral files
 */
trait HasCollaterals {
  /**
   * Dump collateral files for this component to the specified output directory.
   * Inheriting classes should call super.dumpCollaterals with their own subdirectory name.
   *
   * @param outDir  The output directory path
   * @param dirName Optional subdirectory name for this component's collaterals - if None, no directory is created
   * @return Some(Path) to the collaterals directory if created, None otherwise
   */
  def dumpCollaterals(outDir: Path, dirName: Option[String] = None): Option[Path] = {
    if (dirName.isDefined) {
      val collateralsDir = outDir.resolve(dirName.get)
      if (!collateralsDir.toFile.exists()) {
        Files.createDirectories(collateralsDir)
      }
      return Some(collateralsDir)
    }
    None
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
 * Case class representing a PMOD pin on the FPGA board, including its package pin name and I/O standard.
 * @param packagePin The name of the package pin corresponding to this PMOD pin (e.g., "G8", "H8", etc.)
 * @param ioStandard The I/O standard for this PMOD pin (e.g., "LVCMOS33")
 * @param pin The raw PMOD pin index (0-7) corresponding to this PMOD pin
 */
case class FPGAPMODPin(packagePin: String, ioStandard: String, pin: Int) extends BasePMODPin {
  override def toRaw: RawPMODPin = RawPMODPin(pin)

  override def toFPGA(pmodPort: Int, fpga: FPGA): FPGAPMODPin = {
    // This pin is already in FPGA format, so just return it
    this
  }
}



trait WantsPMODPins {

  this: BdPinPort =>

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


/**
 * Trait for components that need finalization before TCL generation
 * This allows components to inject subcomponents based on their connections
 */
trait Finalizable {
  this: BdBaseComp =>
  /**
   * Implementation of finalizeBd, to be provided by inheriting classes.
   * You must not instantiate Finalizable subcomponents here as order of finalization is not guaranteed.
   *
   * @return A sequence of BdComp subcomponents and TCLCommands to be added to the design.
   */
  protected def finalizeBdImpl(): Unit


  /** Called by SOCTBdBuilder before generating TCL to allow components to create/register subcomponents. */
  final def finalizeBd(): Unit = {
    finalizeBdImpl()
  }
}


trait HasDTSInfo {
  /**
   * The DTSInfo describing how this component should appear in the generated device tree.
   *
   * @return The DTSInfo for this component, including compatible strings, register regions, interrupts, etc.
   */
  def dtsInfo: DTSInfo
}


/**
 * Trait for components that have a BD address that needs to be assigned before TCL generation
 */
trait HasBdAddr {
  this: BdComp =>
  /**
   * TCL commands to assign the address for this component in the block design.
   *
   * @return TCL commands
   */
  def assignAddrTcl: TCLCommands
}


trait HasAxiSlave extends HasBdAddr {
  this: BdComp =>

  /**
   * The AXI master pin to which this slave should be connected in the block design.
   *
   * @return The AXI master pin (e.g., from the RocketChip external MMIO port) to connect to this slave in the block design.
   */
  def getAxiMasterPin: BdIntfPin

  /**
   * The AXI slave interface pin of this component, to be connected in the block design.
   *
   * @return The AXI slave interface pin of this component, to be connected in the block design.
   */
  def S_AXI: BdIntfPin
}


trait HasAxiMaster extends HasBdAddr {
  this: BdComp =>

  /**
   * The AXI slave pins to which this master should be connected in the block design, along with the corresponding slave register names for address assignment.
   *
   * @return A sequence of (slave pin, slave reg name) pairs to connect to this master and assign addresses for.
   */
  def getAxiSlavePins: Seq[(BdIntfPin, String)]

  /**
   * The AXI master interface pin of this component, to be connected in the block design.
   *
   * @return The AXI master interface pin of this component, to be connected in the block design.
   */
  def M_AXI: BdIntfPin
}


/**
 * Trait for Chisel modules that need finalization before TCL generation.
 * Will be called after the Chisel modules are elaborated and before all other finalization.
 */
trait ChiselModuleTop extends BdComp with Finalizable


/**
 * Trait for components that have a friendly name
 */
trait HasFriendlyName {
  /**
   * A friendly name for this component, derived from the class name or overridden
   */
  val friendlyName: String = this.getClass.getSimpleName.replaceAll("\\$", "")
}