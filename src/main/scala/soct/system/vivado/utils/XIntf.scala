package soct.system.vivado.utils

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands}


/**
 * Base class for Board Design X Interfaces.
 * Used to add extra annotations to ports in the design.
 */
abstract class XIntfPortMapping(implicit bd: SOCTBdBuilder, p: Parameters) extends BdBaseComp with XIntf {
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
 * Class for Xilinx Board Interface Ports - used to connect components to board interfaces like DDR4, Ethernet, etc.
 */
abstract class XIntfPort(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain])
  extends BdComp() with XIntf with SourceForSinks {
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
    val source = BdIntfPort(instanceName, this)
    BdPinBase.connect(source, sinkPins)
  }
}