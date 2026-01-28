package soct.system.vivado.abstracts

import soct.system.vivado.{StringToTCLCommand, TCLCommand, TCLCommands, XilinxDesignException}

/**
 * Base class for Board Design Pins, representing a port on a component instance.
 */
abstract class BdPinBase(portFn: () => String, instFn: () => BdComp) extends HasFriendlyName {
  lazy val port: String = portFn()
  lazy val inst: BdComp = instFn()

  override def friendlyName: String = s"${inst.instanceName}/$port"

  override def toString: String = friendlyName
}

object BdPinBase {

  def connect(sourcePin: BdPinBase, sinkPins: Iterable[BdPinBase]): TCLCommands = {
    sinkPins.map(sinkPin => connect(sourcePin, sinkPin)).toSeq
  }

  def connect(sourcePin: BdPinBase, sinkPin: BdPinBase): TCLCommand = {
    val command = (sourcePin, sinkPin) match {
      // -------- Interface connections --------
      case (_: BdIntfPin, _: BdIntfPin) =>
        s"connect_bd_intf_net [get_bd_intf_pins $sourcePin] [get_bd_intf_pins $sinkPin]"

      case (_: BdIntfPort, _: BdIntfPort) =>
        s"connect_bd_intf_net [get_bd_intf_ports $sourcePin] [get_bd_intf_ports $sinkPin]"

      case (_: BdIntfPin, _: BdIntfPort) =>
        s"connect_bd_intf_net [get_bd_intf_pins $sourcePin] [get_bd_intf_ports $sinkPin]"

      case (_: BdIntfPort, _: BdIntfPin) =>
        s"connect_bd_intf_net [get_bd_intf_ports $sourcePin] [get_bd_intf_pins $sinkPin]"

      // -------- ERROR: interface vs net --------
      case (_: BdIntfPin | _: BdIntfPort, _) |
           (_, _: BdIntfPin | _: BdIntfPort) =>
        throw new XilinxDesignException(
          s"BD autoconnect pin type mismatch: source=$sourcePin sink=$sinkPin (interface vs net)"
        )

      // -------- Scalar net connections --------
      case (_: BdPin, _: BdPin) =>
        s"connect_bd_net [get_bd_pins $sourcePin] [get_bd_pins $sinkPin]"

      case (_: BdPort, _: BdPort) =>
        s"connect_bd_net [get_bd_ports $sourcePin] [get_bd_ports $sinkPin]"

      case (_: BdPin, _: BdPort) =>
        s"connect_bd_net [get_bd_pins $sourcePin] [get_bd_ports $sinkPin]"

      case (_: BdPort, _: BdPin) =>
        s"connect_bd_net [get_bd_ports $sourcePin] [get_bd_pins $sinkPin]"
    }
    command.tcl
  }
}

final class BdPin private(portFn: () => String, instFn: () => BdComp) extends BdPinBase(portFn, instFn)

object BdPin {
  def apply(port: => String, inst: => BdComp): BdPin =
    new BdPin(() => port, () => inst)
}

final class BdPort private(portFn: () => String, instFn: () => BdComp) extends BdPinBase(portFn, instFn) {
  override def friendlyName: String = port
}

object BdPort {
  def apply(port: => String, inst: => BdComp): BdPort = new BdPort(() => port, () => inst)
}

final class BdIntfPin private(portFn: () => String, instFn: () => BdComp) extends BdPinBase(portFn, instFn)

object BdIntfPin {
  def apply(port: => String, inst: => BdComp): BdIntfPin =
    new BdIntfPin(() => port, () => inst)
}

final class BdIntfPort private(portFn: () => String, instFn: () => BdComp) extends BdPinBase(portFn, instFn) {
  override def friendlyName: String = port
}

object BdIntfPort {
  def apply(port: => String, inst: => BdComp): BdIntfPort =
    new BdIntfPort(() => port, () => inst)
}
