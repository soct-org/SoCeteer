package soct.system.vivado.abstracts

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands, XilinxDesignException}


/**
 * Base class for Board Design Components.
 * DO NOT add val/var members that are overridden in subclasses, as automatically registering the component in the
 * builder happens in this base class constructor - before subclass constructors run. Only defs are safe to use, as
 * they are resolved at call time.
 */
abstract class BdBaseComp(implicit bd: SOCTBdBuilder, p: Parameters) extends HasFriendlyName {
  // Register this component with the BDBuilder upon creation
  bd.addNode(this)

  if (bd.inFinalization && this.isInstanceOf[Finalizable]) {
    throw XilinxDesignException(s"Finalizable component $this was created during BdBuilder finalization. " +
        s"This is not allowed because finalization order is not guaranteed.\n" +
        s"Fix: create it before finalizeDesign(), or make it non-Finalizable."
    )
  }

  override final def equals(other: Any): Boolean =
    this eq other.asInstanceOf[AnyRef]


  override final def hashCode(): Int =
    System.identityHashCode(this)

}


/**
 * Trait for components that can be instantiated in the design
 */
abstract class BdComp(implicit bd: SOCTBdBuilder, p: Parameters) extends BdBaseComp {

  /**
   * Optional index to differentiate multiple instances of the same component - Must be a val to ensure stable value.
   */
  val index: Int = bd.countInstancesOf(this)

  /**
   * The instance name for this component. By default, use the friendly name converted to snake_case with an optional index suffix.
   *
   * @return The instance name
   */
  def instanceName: String = {
    val name = camelToSnake.replaceAllIn(friendlyName, "$1_$2").toLowerCase
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

  private val camelToSnake = "([a-z])([A-Z])".r
}