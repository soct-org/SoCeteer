package soct.system.vivado.abstracts

import chisel3.Data
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommand, TCLCommands, XilinxDesignException}


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
trait BiDirNet extends DrivesNet with DrivenByNet


/**
 * Base class for Board Design Pins, representing a port or pin on a component.
 */
trait BdPinPort extends ConnectOps {

  /** The parent component instance for this. For ports, this is the component itself. */
  val parentInst: BdComp

  /** How to reference this in TCL commands */
  val ref: String

  /** How Vivado should retrieve this endpoint */
  val vivadoKind: VivadoHandleKind


  /**
   * Check for structural equality with another BdPinPort.
   * This requires that the BdBuilder is locked, so that instance names are stable.
   *
   * @param that the other BdPinPort to compare against
   * @param bd   the BdBuilder context
   * @return true if the two BdPinPorts refer to the same pin/port on the same component instance
   */
  def sameAs(that: BdPinPort)(implicit bd: SOCTBdBuilder): Boolean = {
    if (!bd.locked) {
      throw new XilinxDesignException("BdPinPort.sameAs can only be called after the BdBuilder is locked. " +
        "Before locking, the instance names may change, so equality cannot be determined.")
    }
    this.ref == that.ref
  }
}


object BdPinPort {

  // -------------------------------------------------
  // Interface connections (AutoConnect only)
  // -------------------------------------------------

  implicit def intfPinAuto[A <: BdIntfPin, B <: BdIntfPin]: AutoConnect[A, B] =
    (ths, that, bd) => bd.addEdge(ths, that)

  implicit def anyToIntfPortAuto[A <: BdPinPort, B <: BdIntfPortBase]: AutoConnect[A, B] =
    (ths, that, bd) => bd.addEdge(ths, that)

  implicit def intfPortToAnyAuto[A <: BdIntfPortBase, B <: BdPinPort]: AutoConnect[A, B] =
    (ths, that, bd) => bd.addEdge(ths, that)

  // -------------------------------------------------
  // Scalar bidirectional pins (AutoConnect)
  // -------------------------------------------------

  implicit def inoutAuto[A <: BiDirNet, B <: BiDirNet]: AutoConnect[A, B] =
    (a, b, bd) => bd.addEdge(a, b)

  // -------------------------------------------------
  // Scalar directional connections (ToSinkConnect)
  // -------------------------------------------------

  // source (drives) --> sink (driven)
  implicit def drivesToDriven[A <: DrivesNet, B <: DrivenByNet]: ToSinkConnect[A, B] =
    (a, b, bd) => bd.addEdge(a, b)


  // -------------------------------------------------
  // Scalar directional connections (ToSourceConnect)
  // -------------------------------------------------

  // sink (driven) <-- source (drives)
  implicit def drivenFromDrives[A <: DrivenByNet, B <: DrivesNet]: ToSourceConnect[A, B] =
    (a, b, bd) => bd.addEdge(b, a)



  private def snake(name: String): String = {
    name.toLowerCase.replace(".", "_")
  }

  private def vivadoGetExpr(p: BdPinPort): String = {
    val x = p.ref.replaceAll("\\[(\\d+)]", "_$1") // Replace all occurrences of [N] with _N (mainly chisel Vecs)
    p.vivadoKind match {
      case VivadoHandleKind.ScalarPin => s"[get_bd_pins $x]"
      case VivadoHandleKind.ScalarPort => s"[get_bd_ports $x]"
      case VivadoHandleKind.IntfPin => s"[get_bd_intf_pins $x]"
      case VivadoHandleKind.IntfPort => s"[get_bd_intf_ports $x]"
    }
  }

  /** Convert a Chisel Data port to its name in Verilog */
  def portToPortName(x: Data): String = {
    snake(x.instanceName)
  }

  /** Convert a Chisel Data port to a BdPin */
  def portToBdPin[T <: Data](x: => T)(implicit bd: SOCTBdBuilder): BdChiselPin = {
    new BdChiselPin(snake(x.instanceName), bd.topInstance(), x)
  }

  def connect(source: BdPinPort, sinks: Iterable[BdPinPort]): TCLCommands = {
    sinks.map(sink => connect(source, sink)).toSeq
  }

  def connect(source: BdPinPort, sink: BdPinPort): TCLCommand = {
    val isIntf = source.vivadoKind match {
      case VivadoHandleKind.IntfPin | VivadoHandleKind.IntfPort => true
      case _ => false
    }

    val cmd = if (isIntf) s"connect_bd_intf_net ${vivadoGetExpr(source)} ${vivadoGetExpr(sink)}"
    else s"connect_bd_net      ${vivadoGetExpr(source)} ${vivadoGetExpr(sink)}"

    cmd.tcl
  }
}