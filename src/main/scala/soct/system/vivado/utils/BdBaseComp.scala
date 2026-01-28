package soct.system.vivado.utils

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands}


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