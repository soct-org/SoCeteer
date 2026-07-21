package soct.system.vivado.abstracts

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands, VivadoDesignException}

import scala.reflect.ClassTag


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
    throw VivadoDesignException(s"Finalizable component $this was created during BdBuilder finalization. " +
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
   * WARNING: this is only the LEAF name (and the TCL variable holding the cell object).
   * Any TCL that names the cell or its pins by path must use [[bdPath]] instead -
   * `instanceName` silently breaks the moment the component is placed in a hierarchy
   * via [[withGroup]] (pin references get this for free through `BdPinBase.ref`).
   *
   * @return The instance name
   */
  lazy val instanceName: String = {
    customNameOpt.getOrElse(camelToSnake.replaceAllIn(friendlyName, "$1_$2").toLowerCase + s"_$index")
  }


  /**
   * Return this component with a custom instance name. This allows users to specify a fixed name instead of the default generated one.
   * @param name The custom instance name to use for this component. Must be unique across the design to avoid naming conflicts (no checks are performed).
   * @return this with the custom instance name applied
   */
  def withInstanceName(name: String): this.type = {
    customNameOpt = Some(name)
    this
  }

  /**
   * Place this component inside a named block-design hierarchy (a Vivado
   * `create_bd_cell -type hier` cell). Purely organizational: the schematic groups the
   * cell under `<group>/`, pin references become hierarchical, the netlist is unchanged.
   * The builder creates each distinct hierarchy once, before any member cell.
   *
   * @param name the hierarchy name (shared by every member; no nesting support)
   * @return this with the hierarchy applied
   */
  def withGroup(name: String): this.type = {
    groupOpt = Some(name)
    this
  }

  /** The hierarchy this component lives in, if any (see [[withGroup]]). */
  def group: Option[String] = groupOpt

  /** The full block-design path of this cell: `<group>/<instanceName>` inside a
   * hierarchy, the bare instance name at top level. Everything that names the cell or
   * its pins in TCL must go through this (see BdPinBase.ref). */
  final def bdPath: String = groupOpt.map(g => s"$g/$instanceName").getOrElse(instanceName)

  /**
   * Default properties for this component influenced by parameters, can be overridden by subclasses
   *
   * @return A map of default properties
   */
  def defaultProperties: Map[String, String] = Map.empty

  /**
   * Emit the TCL command to instantiate this component in the design.
   *
   * @return the `create_bd_cell` command for this component
   * @throws soct.system.vivado.VivadoDesignException if the component is neither [[IsXilinx]] nor [[IsModule]]
   */
  def instTcl: TCLCommands = {
    this match {
      case x: IsXilinx =>
        Seq(s"set $instanceName [create_bd_cell -type ${x.tpe} -vlnv ${x.partName} $bdPath]".tcl)
      case module: IsModule =>
        Seq(s"set $instanceName [create_bd_cell -type module -reference ${module.reference} $bdPath]".tcl)
      case _ =>
        throw VivadoDesignException(s"Component $friendlyName must be either IsXilinxIP or IsModule to be instantiated.")
    }
  }

  // Optional custom instance name provided by the user set via withInstanceName
  private var customNameOpt: Option[String] = None

  // Optional hierarchy set via withGroup
  private var groupOpt: Option[String] = None

  // Regex to convert CamelCase to snake_case for default instance names
  private val camelToSnake = "([a-z])([A-Z])".r
}