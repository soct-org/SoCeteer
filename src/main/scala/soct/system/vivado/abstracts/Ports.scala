package soct.system.vivado.abstracts

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands}

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

  final override def withInstanceName(name: String): BdPortBase.this.type = throw new UnsupportedOperationException("BdVirtualPort instance name cannot be changed after creation")
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
   * Emit the TCL command to create the port for this component
   */
  override def instTcl: TCLCommands = {
    // Either none or both of from/to must be defined
    val range = (from, to) match {
      case (Some(f), Some(t)) => s"-from $f -to $t "
      case (None, None) => ""
      case _ => throw new IllegalStateException(s"BdPort $instanceName must have either both or neither of from/to defined")
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
 * Class for Xilinx Board Interface Ports - used to connect components to board interfaces like DDR4, Ethernet, etc.
 */
abstract class BdIntfPortBase(implicit bd: SOCTBdBuilder, p: Parameters) extends BdPortBase with XIntf {
  /**
   * The mode of this interface port, e.g., "Master" or "Slave"
   */
  val mode: String

  /**
   * Emit the TCL command to create the port for this component
   */
  override def instTcl: TCLCommands = {
    Seq(s"set $instanceName [create_bd_intf_port -mode $mode -vlnv $partName $instanceName]".tcl)
  }

  override val vivadoKind: VivadoHandleKind = VivadoHandleKind.IntfPort
}


/**
 * Master Board Interface Port
 */
abstract class BdIntfPortMaster(override val mode: String = "Master")(implicit bd: SOCTBdBuilder, p: Parameters) extends BdIntfPortBase


/**
 * Slave Board Interface Port
 */
abstract class BdIntfPortSlave(override val mode: String = "Slave")(implicit bd: SOCTBdBuilder, p: Parameters) extends BdIntfPortBase


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
