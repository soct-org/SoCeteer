package soct.xilinx.components

import org.chipsalliance.cde.config.Parameters
import soct.{ChiselTop, HasXilinxFPGA}
import soct.xilinx.XilinxDesignException

import java.io.File
import java.nio.file.{Files, Path}
import scala.collection.mutable


trait Component {
  /**
   * Check that this component is available in the current configuration.
   */
  @throws[XilinxDesignException]
  def checkAvailable(top: ChiselTop)(implicit p: Parameters): Unit = {
    val fpgaOpt = p(HasXilinxFPGA)
    if (fpgaOpt.isEmpty) {
      throw XilinxDesignException(s"Adding $friendlyName requires the design to run on a Xilinx FPGA")
    }
  }

  /**
   * A friendly name for this component, derived from the class name or overridden
   */
  val friendlyName: String = this.getClass.getSimpleName.replaceAll("\\$", "")

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
}

/**
 * Trait for custom module components
 */
trait IsModule extends Component {
  /**
   * The reference name of this module - as defined in the collateral files
   */
  val reference: String
}

/**
 * Trait for Xilinx IP components
 */
trait IsXilinxIP extends Component {
  /**
   * The part name of this Xilinx IP
   */
  val partName: String
}

/**
 * Trait for Board Design Ports - used to connect components to board ports like clocks, resets, etc.
 */
trait BdPort extends Component {

  /**
   * The name of this interface port
   */
  val INTERFACE_NAME: String

  /**
   * The type of this interface port, e.g., "clk", "data", etc.
   */
  val ifType: String

  /**
   * The direction of this interface port, e.g., "I", "O", or "IO"
   */
  val dir: String

  /**
   * Optional vector range for this port, e.g. -from 3
   */
  val from: Option[String] = None

  /**
   * Optional vector range for this port, e.g. -to 0
   */
  val to: Option[String] = None

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
 * Trait for Xilinx Board Interface Ports - used to connect components to board interfaces like DDR4, Ethernet, etc.
 */
trait XilinxBdIntfPort extends IsXilinxIP {
  /**
   * The name of this interface, used to connect components to it
   */
  val INTERFACE_NAME: String

  /**
   * The mode of this interface, e.g., "Master" or "Slave"
   */
  val mode: String

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
trait InstantiableComponent extends Component {

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