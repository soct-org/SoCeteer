package soct.xilinx.components

import org.chipsalliance.cde.config.Parameters
import soct.{ChiselTop, HasXilinxFPGA}
import soct.xilinx.{BDBuilder, XilinxDesignException}

import java.io.File
import java.nio.file.{Files, Path}
import scala.collection.mutable

/**
 * Base class for Board Design Components.
 * DO NOT add val/var members that are overridden in subclasses, as automatically registering the component in the
 * builder happens in this base class constructor - before subclass constructors run. Only defs are safe to use, as
 * they are resolved at call time.
 */
abstract class BdComp()(implicit bd: BDBuilder, p: Parameters, top: ChiselTop) extends HasFriendlyName {
  /**
   * Check that this component is available in the current configuration.
   */
  @throws[XilinxDesignException]
  def checkAvailable(): Unit = {
    val fpgaOpt = p(HasXilinxFPGA)
    if (fpgaOpt.isEmpty) {
      throw XilinxDesignException(s"Adding $friendlyName requires the design to run on a Xilinx FPGA")
    }
  }

  /**
   * Default properties for this component influenced by parameters, can be overridden by subclasses
   *
   * @return A map of default properties
   */
  def defaultProperties: Map[String, String] = Map.empty

  /**
   * Constraints for this component, to be added to the design
   *
   * @return A sequence of constraint strings
   */
  def constraints: Seq[String] = Seq.empty

  /**
   * Dump collateral files for this component to the specified output directory.
   * Inheriting classes should call super.dumpCollaterals with createDir = true to ensure the directory exists.
   *
   * @param outDir    The output directory path
   * @param createDir Whether to create the directory if it does not exist
   * @return Some(Path) to the created directory if createDir is true, None otherwise
   */
  def dumpCollaterals(outDir: Path, createDir: Boolean = false): Option[Path] = {
    if (createDir) {
      val collateralsDir = outDir.resolve(friendlyName)
      if (!collateralsDir.toFile.exists()) {
        Files.createDirectories(collateralsDir)
      }
      return Some(collateralsDir)
    }
    None
  }

  // Register this component with the BDBuilder upon creation
  bd.add(this)
}

/**
 * Class for Board Design Ports - used to connect components to board ports like clocks, resets, etc.
 */
abstract class BdPort(implicit bd: BDBuilder, p: Parameters, top: ChiselTop) extends BdComp {

  /**
   * The name of this interface port
   */
  def INTERFACE_NAME: String

  /**
   * The type of this interface port, e.g., "clk", "data", etc.
   */
  def ifType: String

  /**
   * The direction of this interface port, e.g., "I", "O", or "IO"
   */
  def dir: String

  /**
   * Optional vector range for this port, e.g. -from 3
   */
  def from: Option[String] = None

  /**
   * Optional vector range for this port, e.g. -to 0
   */
  def to: Option[String] = None

  /**
   * Emit the TCL command to create the port for this component
   */
  def tclCommands: Seq[String] = {
    // Either none or both of from/to must be defined
    val range = (from, to) match {
      case (Some(f), Some(t)) => s"-from $f -to $t "
      case (None, None) => ""
      case _ => throw new IllegalStateException(s"BdPort $INTERFACE_NAME must have either both or neither of from/to defined")
    }
    Seq(s"set ${INTERFACE_NAME} [create_bd_port -type $ifType -dir $dir $range$INTERFACE_NAME]")
  }
}

/**
 * Class for Xilinx Board Interface Ports - used to connect components to board interfaces like DDR4, Ethernet, etc.
 */
abstract class XilinxBdIntfPort(implicit bd: BDBuilder, p: Parameters, top: ChiselTop) extends BdComp with IsXilinxIP {
  /**
   * The name of this interface, used to connect components to it
   */
  def INTERFACE_NAME: String

  /**
   * The mode of this interface, e.g., "Master" or "Slave"
   */
  def mode: String

  /**
   * Emit the TCL command to create the port for this component
   */
  def tclCommands: Seq[String] = {
    Seq(s"set $INTERFACE_NAME [create_bd_intf_port -mode $mode -vlnv $partName $INTERFACE_NAME]")
  }
}

/**
 * Trait for components that can be instantiated in the design
 */
abstract class InstantiableBdComp(implicit bd: BDBuilder, p: Parameters, top: ChiselTop) extends BdComp {

  /**
   * Optional index to differentiate multiple instances of the same component
   */
  def index: Option[Int] = None

  /**
   * The instance name for this component. By default, use the friendly name converted to snake_case with an optional index suffix.
   *
   * @return The instance name
   */
  def instanceName: String = {
    val name = friendlyName.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase
    index match {
      case Some(i) => s"${name}_$i"
      case None => name
    }
  }

  /**
   * Emit the TCL command to instantiate this component in the design
   */
  def tclCommands: Seq[String] = {
    this match {
      case ip: IsXilinxIP =>
        Seq(s"set $instanceName [create_bd_cell -type ip -vlnv ${ip.partName} $instanceName]")
      case module: IsModule =>
        Seq(s"set $instanceName [create_bd_cell -type module -reference ${module.reference} $instanceName]")
      case _ =>
        throw new UnsupportedOperationException(s"Component ${friendlyName} must be either IsXilinxIP or IsModule to be instantiated.")
    }
  }

}


/**
 * Trait for custom module components
 */
trait IsModule {
  /**
   * The reference name of this module - as defined in the collateral files
   */
  def reference: String
}

/**
 * Trait for Xilinx IP components
 */
trait IsXilinxIP  {
  /**
   * The part name of this Xilinx IP
   */
  def partName: String
}

trait HasFriendlyName {
  /**
   * A friendly name for this component, derived from the class name or overridden
   */
  def friendlyName: String = this.getClass.getSimpleName.replaceAll("\\$", "")
}