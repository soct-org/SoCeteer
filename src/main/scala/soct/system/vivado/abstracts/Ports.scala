package soct.system.vivado.abstracts

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommand, TCLCommands, VivadoDesignException}

/**
 * Trait for Board Design Pin Ports - used to connect component ports.
 * Ports are always BdComps themselves.
 */
abstract class BdPortBase(implicit bd: SOCTBdBuilder, p: Parameters) extends BdComp with BdPinPort {
  override lazy val parentInst: BdComp = this

  def portName: String

  final override val index: Int = 0 // Ports are not indexed - the portName must be unique across the design

  final override lazy val instanceName: String = portName

  override lazy val ref: String = portName

  /**
   * Ports derive their instance name from `portName`; it cannot be replaced.
   *
   * @throws soct.system.vivado.VivadoDesignException always
   */
  final override def withInstanceName(name: String): BdPortBase.this.type = throw VivadoDesignException("Port instance names cannot be changed after creation")
}


/**
 * Class for Board Design Ports - used to connect components to board ports like clocks, resets, etc.
 */
sealed abstract class BdVirtualPort(implicit bd: SOCTBdBuilder, p: Parameters) extends BdPortBase {

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
   * Emit the TCL command to create the port for this component.
   *
   * @return the `create_bd_port` command for this port
   * @throws soct.system.vivado.VivadoDesignException if only one of `from`/`to` is defined
   */
  override def instTcl: TCLCommands = {
    // Either none or both of from/to must be defined
    val range = (from, to) match {
      case (Some(f), Some(t)) => s"-from $f -to $t "
      case (None, None) => ""
      case _ => throw VivadoDesignException(s"BdPort $instanceName must have either both or neither of from/to defined")
    }
    Seq(s"set $instanceName [create_bd_port -type $ifType -dir $dir $range$instanceName]".tcl)
  }

  final override val vivadoKind: VivadoHandleKind = VivadoHandleKind.ScalarPort
}


abstract class BdVirtualPortI(implicit bd: SOCTBdBuilder, p: Parameters) extends BdVirtualPort with DrivesNet {
  final override def dir: String = "I"
}


abstract class BdVirtualPortO(implicit bd: SOCTBdBuilder, p: Parameters) extends BdVirtualPort with DrivenByNet {
  final override def dir: String = "O"
}


abstract class BdVirtualPortIO(implicit bd: SOCTBdBuilder, p: Parameters) extends BdVirtualPort with BiDirNet {
  final override def dir: String = "IO"
}


/**
 * Whether an interface port is the master or the slave side of its bus.
 */
sealed abstract class PortMode(private[vivado] val tcl: String)

object PortMode {
  case object Master extends PortMode("Master")

  case object Slave extends PortMode("Slave")
}


/**
 * How an external interface port comes into existence in the block design.
 */
sealed trait PortCreation

object PortCreation {

  /**
   * Standalone `create_bd_intf_port` during the instantiation phase. The port's physical
   * signal widths come from the bus-definition DEFAULTS, and Vivado never resizes them
   * afterwards - fine for interfaces with fixed widths (clocks, UART).
   */
  case object Declare extends PortCreation

  /**
   * `make_bd_intf_pins_external` on the port's single peer pin, during the connect phase
   * (after all IP properties are applied). This clones the pin's live signal widths into the
   * port - required for interfaces whose widths depend on the IP configuration. E.g. a
   * dual-rank DDR4 DIMM widens cs_n/cke/odt/ck on the controller, while a Declare'd port
   * would stay single-rank and fail at opt_design with "[Mig 66-99] ... not connected to top
   * level".
   *
   * Because the port only exists from the connect phase on, its `defaultProperties` are
   * applied right after its creation instead of in the global property phase.
   */
  case object Externalize extends PortCreation
}


/**
 * An external interface port of the block design (DDR4, clocks, UART, ...): how it is named,
 * which bus interface it speaks ([[partName]]), which side of the bus it is ([[mode]]) and how
 * it comes into existence ([[creation]]).
 *
 * Use [[BdIntfPort.apply]] for a plain port; subclass for ports with extra behavior (e.g.
 * board clock ports adding a frequency property).
 */
abstract class BdIntfPort(implicit bd: SOCTBdBuilder, p: Parameters) extends BdPortBase with XIntf {

  /** Which side of the bus this port is. */
  def mode: PortMode

  /** How this port comes into existence in the block design. */
  def creation: PortCreation = PortCreation.Declare

  /**
   * Emit the TCL command to create the port. Externalized ports emit nothing here - they are
   * created by their connection instead (see [[externalizeTcl]]).
   *
   * @return the `create_bd_intf_port` command, or nothing for externalized ports
   */
  final override def instTcl: TCLCommands = creation match {
    case PortCreation.Declare =>
      Seq(s"set $instanceName [create_bd_intf_port -mode ${mode.tcl} -vlnv $partName $instanceName]".tcl)
    case PortCreation.Externalize =>
      Seq.empty
  }

  /**
   * TCL that creates this externalized port from its single peer pin and applies the port's
   * properties (which cannot be applied earlier - the port does not exist before this command).
   * The port name is set via -name directly (make_bd_intf_pins_external does not reliably
   * return the created port object across Vivado versions). Emitted as a single multi-line
   * command so the statements stay together when the connect commands are sorted.
   *
   * @param pin the peer pin to externalize
   * @return the creation command
   * @throws soct.system.vivado.VivadoDesignException if the peer is not an interface pin or the port has more than one connection
   */
  private[vivado] def externalizeTcl(pin: BdPinPort): TCLCommand = {
    if (pin.vivadoKind != VivadoHandleKind.IntfPin) {
      throw VivadoDesignException(s"Externalized port $instanceName must be connected to an interface pin, but got ${pin.ref} (${pin.vivadoKind}).")
    }
    val connections = bd.connectors(this).size
    if (connections != 1) {
      throw VivadoDesignException(s"Externalized port $instanceName must have exactly one connection (it is created FROM its peer pin), but has $connections.")
    }
    val props =
      if (defaultProperties.isEmpty) ""
      else "\n" + s"set_property -dict [list ${defaultProperties.map { case (k, v) => s"$k {$v}" }.mkString(" ")}] $$$instanceName"
    s"""make_bd_intf_pins_external -name $instanceName ${BdPinPort.vivadoGetExpr(pin)}
       |set $instanceName [get_bd_intf_ports $instanceName]$props""".stripMargin.tcl
  }

  override val vivadoKind: VivadoHandleKind = VivadoHandleKind.IntfPort
}

object BdIntfPort {

  /**
   * Create a plain interface port.
   *
   * @param portName the port name in the block design (must be unique across the design)
   * @param partName the interface VLNV (e.g. "xilinx.com:interface:ddr4_rtl:1.0")
   * @param mode     which side of the bus the port is
   * @param creation how the port comes into existence (see [[PortCreation]])
   * @return the port
   */
  def apply(portName: String, partName: String, mode: PortMode,
            creation: PortCreation = PortCreation.Declare)
           (implicit bd: SOCTBdBuilder, p: Parameters): BdIntfPort = {
    val (n, vlnv, m, c) = (portName, partName, mode, creation)
    new BdIntfPort {
      override def portName: String = n

      override def partName: String = vlnv

      override def mode: PortMode = m

      override def creation: PortCreation = c
    }
  }
}


/**
 * Add extra annotations to ports in the design.
 * Registers the port mapping for this interface with the builder when mixed in
 */
trait MapsToPorts {

  implicit val bd: SOCTBdBuilder

  /**
   * The port mapping for this interface - maps signal names to sequences of annotation strings
   */
  def portMapping: Map[String, Seq[String]]


  // Register the port mapping with the builder when this trait is mixed in
  bd.addPortMapping(() => portMapping)
}
