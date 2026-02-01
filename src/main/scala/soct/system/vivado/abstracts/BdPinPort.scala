package soct.system.vivado.abstracts

import chisel3.Data
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommand, TCLCommands}


sealed trait VivadoHandleKind

object VivadoHandleKind {
  case object ScalarPin extends VivadoHandleKind // get_bd_pins

  case object ScalarPort extends VivadoHandleKind // get_bd_ports

  case object IntfPin extends VivadoHandleKind // get_bd_intf_pins

  case object IntfPort extends VivadoHandleKind // get_bd_intf_ports
}


/** Internal connection direction: a thing that can DRIVE a net inside the design */
trait DrivesNet extends BdPinPort


/** Internal connection direction: a thing that can be DRIVEN by a net inside the design */
trait DrivenByNet extends BdPinPort


/** Internal connection direction: bidirectional scalar net */
trait BiDirNet extends BdPinPort


/**
 * Base class for Board Design Pins, representing a port or pin on a component.
 */
trait BdPinPort extends ConnectOps {

  /** The parent component instance for this. For ports, this is the component itself. */
  def parentInst(): BdComp

  /** How to reference this in TCL commands */
  def ref: String

  /** How Vivado should retrieve this endpoint */
  def vivadoKind: VivadoHandleKind

  override def toString: String = ref
}


object BdPinPort {

  // -------------------------------------------------
  // Interface connections (AutoConnect only)
  // -------------------------------------------------

  implicit def intfPinAuto[A <: BdIntfPin, B <: BdIntfPin]: AutoConnect[A, B] =
    (ths, that, bd) => bd.connect(ths, that)

  implicit def anyToIntfPortAuto[A <: BdPinPort, B <: BdIntfPortBase]: AutoConnect[A, B] =
    (ths, that, bd) => bd.connect(ths, that)

  implicit def intfPortToAnyAuto[A <: BdIntfPortBase, B <: BdPinPort]: AutoConnect[A, B] =
    (ths, that, bd) => bd.connect(ths, that)

  // -------------------------------------------------
  // Scalar bidirectional pins (AutoConnect)
  // -------------------------------------------------

  implicit def inoutAuto[A <: BiDirNet, B <: BiDirNet]: AutoConnect[A, B] =
    (a, b, bd) => bd.connect(a, b)

  // -------------------------------------------------
  // Scalar directional connections (ToSinkConnect)
  // -------------------------------------------------

  // source (drives) --> sink (driven)
  implicit def drivesToDriven[A <: DrivesNet, B <: DrivenByNet]: ToSinkConnect[A, B] =
    (a, b, bd) => bd.connect(a, b)

  // source (drives) --> sink (bidir)
  implicit def drivesToBidir[A <: DrivesNet, B <: BiDirNet]: ToSinkConnect[A, B] =
    (a, b, bd) => bd.connect(a, b)

  // source (bidir) --> sink (driven)
  implicit def bidirToDriven[A <: BiDirNet, B <: DrivenByNet]: ToSinkConnect[A, B] =
    (a, b, bd) => bd.connect(a, b)

  // source (bidir) --> sink (bidir)
  implicit def bidirToBidir[A <: BiDirNet, B <: BiDirNet]: ToSinkConnect[A, B] =
    (a, b, bd) => bd.connect(a, b)


  // -------------------------------------------------
  // Scalar directional connections (ToSourceConnect)
  // -------------------------------------------------

  // sink (driven) <-- source (drives)
  implicit def drivenFromDrives[A <: DrivenByNet, B <: DrivesNet]: ToSourceConnect[A, B] =
    (a, b, bd) => bd.connect(b, a)

  // sink (bidir) <-- source (drives)
  implicit def bidirFromDrives[A <: BiDirNet, B <: DrivesNet]: ToSourceConnect[A, B] =
    (a, b, bd) => bd.connect(b, a)

  // sink (driven) <-- source (bidir)
  implicit def drivenFromBidir[A <: DrivenByNet, B <: BiDirNet]: ToSourceConnect[A, B] =
    (a, b, bd) => bd.connect(b, a)

  // sink (bidir) <-- source (bidir)
  implicit def bidirFromBidir[A <: BiDirNet, B <: BiDirNet]: ToSourceConnect[A, B] =
    (a, b, bd) => bd.connect(b, a)


  private def snake(name: String): String = {
    name.toLowerCase.replace(".", "_")
  }

  def vivadoGetExpr(x: BdPinPort): String = x.vivadoKind match {
    case VivadoHandleKind.ScalarPin => s"[get_bd_pins $x]"
    case VivadoHandleKind.ScalarPort => s"[get_bd_ports $x]"
    case VivadoHandleKind.IntfPin => s"[get_bd_intf_pins $x]"
    case VivadoHandleKind.IntfPort => s"[get_bd_intf_ports $x]"
  }


  /** Convert a Chisel Data port to its name in Verilog */
  def portToPortName(x: Data): String = {
    snake(x.instanceName)
  }

  /** Convert a Chisel Data port to a BdPin */
  def portToBdPin(x: Data)(implicit bd: SOCTBdBuilder): BdPinInOut = {
    new BdPinInOut(snake(x.instanceName), bd.topInstance()) // TODO get direction?, for now assume inout. Also, more elegant way to get parent?
  }


  def connect(source: BdPinPort, sinks: Iterable[BdPinPort]): TCLCommands = {
    sinks.map(sink => connect1(source, sink)).toSeq
  }

  def connect1(source: BdPinPort, sink: BdPinPort): TCLCommand = {
    val isIntf = source.vivadoKind match {
      case VivadoHandleKind.IntfPin | VivadoHandleKind.IntfPort => true
      case _ => false
    }

    val cmd =
      if (isIntf) s"connect_bd_intf_net ${vivadoGetExpr(source)} ${vivadoGetExpr(sink)}"
      else s"connect_bd_net      ${vivadoGetExpr(source)} ${vivadoGetExpr(sink)}"

    cmd.tcl
  }
}