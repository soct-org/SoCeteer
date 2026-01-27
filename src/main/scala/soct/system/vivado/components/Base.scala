package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, SOCTVivado, XilinxDesignException}
import soct.XilinxFPGAKey

import java.nio.file.{Files, Path}
import scala.collection.{View, mutable}

/**
 * Base class for Board Design Components.
 * DO NOT add val/var members that are overridden in subclasses, as automatically registering the component in the
 * builder happens in this base class constructor - before subclass constructors run. Only defs are safe to use, as
 * they are resolved at call time.
 */
abstract class BdComp()(implicit bd: SOCTBdBuilder, p: Parameters) extends HasFriendlyName {
  // Register this component with the BDBuilder upon creation
  bd.add(this)
}


/**
 * Trait for components that can be instantiated in the design
 */
abstract class InstantiableBdComp(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain]) extends BdComp {

  /**
   * Optional index to differentiate multiple instances of the same component
   */
  def index: Int = 0

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
  def instTclCommands: Seq[String] = {
    this match {
      case ip: IsXilinxIP =>
        Seq(s"set $instanceName [create_bd_cell -type ${ip.ipType} -vlnv ${ip.partName} $instanceName]")
      case module: IsModule =>
        Seq(s"set $instanceName [create_bd_cell -type module -reference ${module.reference} $instanceName]")
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
 * Base class for Board Design X Interfaces.
 * Used to add extra annotations to ports in the design.
 */
abstract class XIntfPort(implicit bd: SOCTBdBuilder, p: Parameters) extends BdComp {
  /**
   * The name of the signal group for this port, relevant for example for X_INTERFACE_INFO annotations
   */
  def ifName: String

  /**
   * The port mapping for this interface - maps signal names to sequences of annotation strings
   */
  def portMapping: Map[String, Seq[String]]
}


/**
 * Class for Board Design Ports - used to connect components to board ports like clocks, resets, etc.
 */
abstract class VirtualPort(implicit bd: SOCTBdBuilder, p: Parameters)
  extends InstantiableBdComp()(bd, p, None) with SourceForPins {

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
  override def instTclCommands: Seq[String] = {
    // Either none or both of from/to must be defined
    val range = (from, to) match {
      case (Some(f), Some(t)) => s"-from $f -to $t "
      case (None, None) => ""
      case _ => throw new IllegalStateException(s"BdPort $instanceName must have either both or neither of from/to defined")
    }
    Seq(s"set $instanceName [create_bd_port -type $ifType -dir $dir $range$instanceName]")
  }

  override def connectTclCommands: Seq[String] = {
    val prefix = s"connect_bd_net [get_bd_ports $instanceName]"
    sinkPins.map { sink =>
      s"$prefix [get_bd_pins $sink]"
    }.toSeq
  }
}

/**
 * Class for Xilinx Board Interface Ports - used to connect components to board interfaces like DDR4, Ethernet, etc.
 */
abstract class IntfPort(implicit bd: SOCTBdBuilder, p: Parameters)
  extends InstantiableBdComp()(bd, p, None) with IsXilinxIP with SourceForPins {
  /**
   * The mode of this interface, e.g., "Master" or "Slave"
   */
  def mode: String

  /**
   * Emit the TCL command to create the port for this component
   */
  override def instTclCommands: Seq[String] = {
    Seq(s"set $instanceName [create_bd_intf_port -mode $mode -vlnv $partName $instanceName]")
  }

  override def connectTclCommands: Seq[String] = {
    val prefix = s"connect_bd_intf_net [get_bd_intf_ports $instanceName]"
    sinkPins.map { sink =>
      require(sink.isInstanceOf[BdIntfPin], s"IntfPort $this can only connect to BdIntfPin sinks, got $sink")
      s"$prefix [get_bd_intf_pins $sink]"
    }.toSeq
  }
}

abstract class BdPinBase(portFn: () => String, instFn: () => InstantiableBdComp) extends HasFriendlyName {
  lazy val port: String = portFn()
  lazy val inst: InstantiableBdComp = instFn()

  final override def friendlyName: String = s"${inst.instanceName}/$port"

  final override def toString: String = friendlyName
}

final class BdPin private(portFn: () => String, instFn: () => InstantiableBdComp) extends BdPinBase(portFn, instFn)

object BdPin {
  def apply(port: => String, inst: => InstantiableBdComp): BdPin =
    new BdPin(() => port, () => inst)
}

final class BdIntfPin private(portFn: () => String, instFn: () => InstantiableBdComp) extends BdPinBase(portFn, instFn)

object BdIntfPin {
  def apply(port: => String, inst: => InstantiableBdComp): BdIntfPin =
    new BdIntfPin(() => port, () => inst)
}


/**
 * Trait for components that can collect BdPinBases
 */
trait CollectsPins {
  protected val _sinkPins: mutable.Set[BdPinBase] = mutable.Set.empty

  // public view of the collected sink pins
  def sinkPins: View[BdPinBase] = _sinkPins.view

  /**
   * Register a sink BdPinBase that this component provides data to.
   *
   * @param sink The sink BdPinBase
   * @return True if the registration was successful
   */
  def outputTo(sink: BdPinBase): Boolean = {
    _sinkPins += sink
    true
  }

  def outputTo(sink: chisel3.Data)(implicit bd: SOCTBdBuilder): Boolean = {
    _sinkPins += SOCTVivado.portToBdPin(sink)
    true
  }
}


trait HasTCLConnects {
  /**
   * Emit the TCL commands to connect this component in the design
   */
  def connectTclCommands: Seq[String]
}


/**
 * Trait for components that can collect BdPinBases and also provide a source, i.e. connect to them.
 */
trait SourceForPins extends CollectsPins with HasTCLConnects


/**
 * Trait for components that have sink pins
 */
trait HasSinkPins {

  protected val sourcePins = mutable.Set[SourceForPins]()

  /**
   * Get the BdPinBase corresponding to the given source, if any.
   *
   * @param source The source to look up
   * @return Some(BdPinBase) if found, None otherwise
   */
  protected def getPinImpl(source: SourceForPins): Option[BdPinBase]

  /**
   * Get the BdPinBase corresponding to the given source.
   *
   * @param source The source to look up
   * @return The corresponding BdPinBase
   * @throws XilinxDesignException if no BdPinBase is found for the source
   */
  @throws[XilinxDesignException]
  final def getPin(source: SourceForPins): BdPinBase = {
    val sinkOpt = getPinImpl(source)
    if (sinkOpt.isEmpty) {
      throw XilinxDesignException(s"No sink pin found for source $source in component $this")
    }
    sourcePins += source
    sinkOpt.get
  }
}


/**
 * Trait for components that want automatic connection to clock and reset inputs
 */
trait AutoConnect extends ReceivesClock with ReceivesReset


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
 * Trait for Xilinx IP components
 */
trait IsXilinxIP {
  /**
   * The part name of this Xilinx IP
   */
  def partName: String

  /**
   * The interface type of this Xilinx IP, usually "ip", "inline_hdl", etc.
   */
  def ipType: String = "ip"
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
 * Trait for components that have a friendly name
 */
trait HasFriendlyName {
  /**
   * A friendly name for this component, derived from the class name or overridden
   */
  def friendlyName: String = this.getClass.getSimpleName.replaceAll("\\$", "")
}