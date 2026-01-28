package soct.system.vivado.abstracts

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommand, TCLCommands, XilinxDesignException}


/**
 * Base class for Board Design Components.
 * DO NOT add val/var members that are overridden in subclasses, as automatically registering the component in the
 * builder happens in this base class constructor - before subclass constructors run. Only defs are safe to use, as
 * they are resolved at call time.
 */
abstract class BdBaseComp()(implicit bd: SOCTBdBuilder, p: Parameters) extends HasFriendlyName {
  // Register this component with the BDBuilder upon creation
  bd.add(this)
}


/**
 * Trait for components that can be instantiated in the design
 */
abstract class BdComp(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain]) extends BdBaseComp {

  /**
   * Optional index to differentiate multiple instances of the same component
   */
  def index: Int = bd.countInstancesOf(this)

  /**
   * The instance name for this component. By default, use the friendly name converted to snake_case with an optional index suffix.
   *
   * @return The instance name
   */
  def instanceName: String = {
    val name = friendlyName.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase
    s"${name}_$index"
  }

  /**
   * Default properties for this component influenced by parameters, can be overridden by subclasses
   *
   * @return A map of default properties
   */
  def defaultProperties: Map[String, String] = Map.empty

  /**
   * Emit the TCL command to instantiate this component in the design
   */
  def instTcl: TCLCommands = {
    this match {
      case x: IsXilinx =>
        Seq(s"set $instanceName [create_bd_cell -type ${x.tpe} -vlnv ${x.partName} $instanceName]".tcl)
      case module: IsModule =>
        Seq(s"set $instanceName [create_bd_cell -type module -reference ${module.reference} $instanceName]".tcl)
      case _ =>
        throw new UnsupportedOperationException(s"Component $friendlyName must be either IsXilinxIP or IsModule to be instantiated.")
    }
  }

  this match {
    case comp: ReceivesReset =>
      if (dom.isEmpty) {
        soct.log.warn(s"Component $this implements ReceivesReset but has no clock domain provided.")
      }
      if (dom.get.reset.isEmpty) {
        soct.log.warn(s"Component $this implements ReceivesReset but its clock domain has no reset provided.")
      }
      // Connect the reset ports of this component to the reset provider in the clock domain
      dom.foreach(_.reset.foreach {
        case rst: Reset => comp.resetInPorts.foreach(rst.outputTo)
        case rstN: ResetN => comp.resetNInPorts.foreach(rstN.outputTo)
      })
    case _ => // Do nothing
  }

  this match {
    case comp: ReceivesClock =>
      if (dom.isEmpty) {
        soct.log.warn(s"Component $this implements ReceivesClock but has no clock domain provided.")
      }
      dom.foreach { d => comp.clockInPorts.foreach(d.outputTo) }
    case _ => // Do nothing
  }
}

/**
 * Base class for Board Design Pins, representing a port on a component instance.
 */
trait BdPinPort {
  /**
   * The port name for this pin on its parent component. If this is a port, this is the instance name.
   */
  def port(): String

  /**
   * The parent component instance for this pin. If this is a port, the parent is itself.
   */
  def parentInst(): BdComp

  /**
   * The reference string for this pin in TCL commands
   */
  def ref: String

  override def toString: String = ref
}


object BdPinPort {

  def connect(source: BdPinPort, sinks: Iterable[BdPinPort]): TCLCommands = {
    sinks.map(sink => connect(source, sink)).toSeq
  }

  def connect(source: BdPinPort, sink: BdPinPort): TCLCommand = {
    val command = (source, sink) match {
      // -------- Interface connections --------
      case (_: BdIntfPin, _: BdIntfPin) =>
        s"connect_bd_intf_net [get_bd_intf_pins $source] [get_bd_intf_pins $sink]"

      case (_: BdIntfPort, _: BdIntfPort) =>
        s"connect_bd_intf_net [get_bd_intf_ports $source] [get_bd_intf_ports $sink]"

      case (_: BdIntfPin, _: BdIntfPort) =>
        s"connect_bd_intf_net [get_bd_intf_pins $source] [get_bd_intf_ports $sink]"

      case (_: BdIntfPort, _: BdIntfPin) =>
        s"connect_bd_intf_net [get_bd_intf_ports $source] [get_bd_intf_pins $sink]"

      // -------- ERROR: interface vs net --------
      case (_: BdIntfPin | _: BdIntfPort, _) |
           (_, _: BdIntfPin | _: BdIntfPort) =>
        throw new XilinxDesignException(
          s"BD autoconnect pin type mismatch: source=$source sink=$sink (interface vs net)"
        )

      // -------- Scalar net connections --------
      case (_: BdPin, _: BdPin) =>
        s"connect_bd_net [get_bd_pins $source] [get_bd_pins $sink]"

      case (_: BdPort, _: BdPort) =>
        s"connect_bd_net [get_bd_ports $source] [get_bd_ports $sink]"

      case (_: BdPin, _: BdPort) =>
        s"connect_bd_net [get_bd_pins $source] [get_bd_ports $sink]"

      case (_: BdPort, _: BdPin) =>
        s"connect_bd_net [get_bd_ports $source] [get_bd_pins $sink]"
    }
    command.tcl
  }
}