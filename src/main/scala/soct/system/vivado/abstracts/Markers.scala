package soct.system.vivado.abstracts

import soct.system.vivado.{StringToTCLCommand, TCLCommands, XilinxDesignException}
import soct.system.vivado.components.AXIUartLite
import soct.system.vivado.misc.DTSInfo

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