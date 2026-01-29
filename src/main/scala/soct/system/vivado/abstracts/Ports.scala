package soct.system.vivado.abstracts

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands}

/**
 * Trait for Board Design Pin Ports - used to connect component ports
 */
trait BdPortBase extends BdPinPort {
  this: BdComp =>

  override def parentInst(): BdComp = this

  override def ref: String = instanceName
}


/**
 * Class for Board Design Ports - used to connect components to board ports like clocks, resets, etc.
 */
abstract class BdPort(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp()(bd, p, None) with SourceForSinks with BdPortBase {

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

  protected override def connectToSinksImpl: TCLCommands = {
    BdPinPort.connect(this, sinkPins)
  }
}


/**
 * Class for Xilinx Board Interface Ports - used to connect components to board interfaces like DDR4, Ethernet, etc.
 */
abstract class BdIntfPort(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain])
  extends BdComp with XIntf with SourceForSinks with BdPortBase {
  /**
   * The mode of this interface, e.g., "Master" or "Slave"
   */
  def mode: String

  /**
   * Emit the TCL command to create the port for this component
   */
  override def instTcl: TCLCommands = {
    Seq(s"set $instanceName [create_bd_intf_port -mode $mode -vlnv $partName $instanceName]".tcl)
  }

  protected override def connectToSinksImpl: TCLCommands = {
    BdPinPort.connect(this, sinkPins)
  }
}


/**
 * Add extra annotations to ports in the design.
 */
abstract class MapsToPorts(implicit bd: SOCTBdBuilder, p: Parameters) extends BdBaseComp {
  /**
   * The port mapping for this interface - maps signal names to sequences of annotation strings
   */
  def portMapping: Map[String, Seq[String]]
}
