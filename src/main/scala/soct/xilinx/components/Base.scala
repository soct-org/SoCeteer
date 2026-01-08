package soct.xilinx.components

import org.chipsalliance.cde.config.Parameters
import soct.ChiselTop
import soct.xilinx.XilinxDesignException

import java.io.File
import java.nio.file.Path
import scala.collection.mutable


trait Component {

  /**
   * Check that this component is available in the current configuration.
   */
  @throws[XilinxDesignException]
  def checkAvailable(top: ChiselTop)(implicit p: Parameters): Unit = {}

  /**
   * A friendly name for this component, derived from the class name or overridden
   */
  val friendlyName: String = this.getClass.getSimpleName.replaceAll("\\$", "")

  /**
   * A map of properties for this component, to be added to the design
   */
  val properties = mutable.Map.empty[String, String]

  /**
   * Default properties for this component influenced by parameters, can be overridden by subclasses
   *
   * @param parameters The implicit Parameters
   * @return A map of default properties
   */
  def defaultProperties()(implicit parameters: Parameters): Map[String, String] = {
    Map.empty
  }

  /**
   * Constraints for this component, to be added to the design
   *
   * @return A sequence of constraint strings
   */
  def constraints: Seq[String] = Seq.empty


  /**
   * TCL commands to add this component to the design
   *
   * @return A sequence of TCL command strings
   */
  def tclCommands: Seq[String] = Seq.empty


  /**
   * Dump collateral files for this component to the specified output directory
   *
   * @param outDir The output directory path
   */
  def dumpCollaterals(outDir: Path): Unit = {
    // Default implementation does nothing
  }
}

/**
 * Base class for Xilinx IP components
 */
abstract class XilinxIPComponent extends Component
  with IsXilinxIP {

  @throws[XilinxDesignException]
  def connectToBoardInterface(intf: XilinxBdIntfPort): Unit = {}
}


/**
 * Trait for custom module components
 */
trait IsModule {
  _: Component =>

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
 *
 */
trait XilinxBdPort {

  val INTERFACE_NAME: String

  val ifType: String

  val from: Option[String] = None

  val to: Option[String] = None

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
  override def tclCommands: Seq[String] = {
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
  override def tclCommands: Seq[String] = {
    this match {
      case ip: IsXilinxIP =>
        Seq(s"set $instanceName [create_bd_cell -type ip -vlnv ${ip.partName} $instanceName]")
      case module: IsModule =>
        Seq(s"set $instanceName [create_bd_cell -type module -reference ${module.reference} $instanceName]")
      case _ =>
        throw new UnsupportedOperationException(s"Component ${friendlyName} is not instantiable")
    }
  }
}