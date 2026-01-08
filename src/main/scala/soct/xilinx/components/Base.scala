package soct.xilinx.components

import org.chipsalliance.cde.config.Parameters
import soct.xilinx.XilinxDesignException

import scala.collection.mutable


trait Component {

  /**
   * Check that this component is available in the current configuration.
   */
  @throws[XilinxDesignException]
  def checkAvailable()(implicit p: Parameters): Unit = {}

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
}

/**
 * Base class for Xilinx IP components
 */
abstract class XilinxIPComponent extends Component
  with IsXilinxIP {

  @throws[XilinxDesignException]
  def connectToBoardInterface(intf: XilinxBoardInterface): Unit = {}
}


/**
 * Trait for custom module components
 */
trait IsModule {
  _: Component =>

  val reference: String

  val collateralFiles: Seq[String] = Seq.empty
}


/**
 * Trait for Xilinx IP components
 */
trait IsXilinxIP {
  _: Component =>

  /**
   * The part name of this Xilinx IP
   */
  val partName: String
}


trait XilinxBoardInterface {
  _: IsXilinxIP =>
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
  def createPortTCL(): String = {
    s"set $INTERFACE_NAME [create_bd_intf_port -mode $mode -vlnv $partName $INTERFACE_NAME]"
  }
}


trait Instantiable {

  _: Component =>

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
  def createInstanceTCL(): String = {
    this match {
      case ip: IsXilinxIP =>
        s"set ${instanceName} [create_bd_cell -type ip -vlnv ${ip.partName} ${instanceName}]"
      case module: IsModule =>
        s"set ${instanceName} [create_bd_cell -type module -reference ${module.reference} ${instanceName}]"
      case _ =>
        throw new UnsupportedOperationException(s"Component ${friendlyName} is not instantiable")
    }
  }
}