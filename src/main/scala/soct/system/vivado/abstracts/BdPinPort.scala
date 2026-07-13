package soct.system.vivado.abstracts

import chisel3.Data
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommand, TCLCommands, VivadoDesignException}


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
trait BiDirNet extends BdPinPort with DrivesNet with DrivenByNet


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
   * @throws soct.system.vivado.VivadoDesignException if the builder is not locked yet (instance names would be unstable)
   */
  def sameAs(that: BdPinPort)(implicit bd: SOCTBdBuilder): Boolean = {
    if (!bd.locked) {
      throw new VivadoDesignException("BdPinPort.sameAs can only be called after the BdBuilder is locked. " +
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


  implicit def chiselToDriven[A <: Data, B <: DrivenByNet]: ToSinkConnect[A, B] =
    (a, b, bd) => bd.addEdge(portToBdPin(a)(bd), b)


  implicit def drivesToChisel[A <: DrivesNet, B <: Data]: ToSinkConnect[A, B] =
    (a, b, bd) => bd.addEdge(a, portToBdPin(b)(bd))

  // -------------------------------------------------
  // Scalar directional connections (ToSourceConnect)
  // -------------------------------------------------

  // sink (driven) <-- source (drives)
  implicit def drivenFromDrives[A <: DrivenByNet, B <: DrivesNet]: ToSourceConnect[A, B] =
    (a, b, bd) => bd.addEdge(b, a)

  implicit def drivenFromChisel[A <: DrivenByNet, B <: Data]: ToSourceConnect[A, B] =
    (a, b, bd) => bd.addEdge(portToBdPin(b)(bd), a)

  implicit def chiselToDrives[A <: Data, B <: DrivesNet]: ToSourceConnect[A, B] =
    (a, b, bd) => bd.addEdge(portToBdPin(a)(bd), b)

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

  /**
   * Convert a Chisel Data port to its name in the emitted Verilog (lowercase, dots to underscores).
   *
   * @param x the Chisel port
   * @return the Verilog port name
   */
  private[vivado] def portToPortName(x: Data): String = {
    snake(x.instanceName)
  }

  /**
   * Wrap a Chisel Data port of the top module as a block-design pin, so it can be used with
   * the connect operators (`-->`, `<--`, `<->`).
   *
   * @param x  the Chisel port (evaluated lazily; must belong to the elaborated top module)
   * @param bd the builder holding the top instance
   * @return the pin representing the port on the top-level module cell
   */
  def portToBdPin[T <: Data](x: => T)(implicit bd: SOCTBdBuilder): BdChiselPin = {
    new BdChiselPin(snake(x.instanceName), bd.topInstance(), x)
  }

  /**
   * Emit connect commands from one source to several sinks.
   *
   * @param source the driving endpoint
   * @param sinks  the driven endpoints
   * @return one TCL connect command per sink
   * @throws soct.system.vivado.VivadoDesignException if an [[ExternalizedIntfPort]] endpoint is paired with a non-interface pin
   */
  private[vivado] def connect(source: BdPinPort, sinks: Iterable[BdPinPort]): TCLCommands = {
    sinks.map(sink => connect(source, sink)).toSeq
  }

  /**
   * Emit the connect command joining two endpoints, choosing `connect_bd_net` or
   * `connect_bd_intf_net` from the endpoint kind. [[ExternalizedIntfPort]] endpoints are
   * created here (via `make_bd_intf_pins_external`) instead of being connected.
   *
   * @param source the driving endpoint
   * @param sink   the driven endpoint
   * @return the TCL command
   * @throws soct.system.vivado.VivadoDesignException if an [[ExternalizedIntfPort]] endpoint is paired with a non-interface pin
   */
  private[vivado] def connect(source: BdPinPort, sink: BdPinPort): TCLCommand = {
    (source, sink) match {
      case (port: ExternalizedIntfPort, pin) => externalizePin(port.asInstanceOf[BdIntfPortBase], pin)
      case (pin, port: ExternalizedIntfPort) => externalizePin(port.asInstanceOf[BdIntfPortBase], pin)
      case _ =>
        val isIntf = source.vivadoKind match {
          case VivadoHandleKind.IntfPin | VivadoHandleKind.IntfPort => true
          case _ => false
        }

        val cmd = if (isIntf) s"connect_bd_intf_net ${vivadoGetExpr(source)} ${vivadoGetExpr(sink)}"
        else s"connect_bd_net      ${vivadoGetExpr(source)} ${vivadoGetExpr(sink)}"

        cmd.tcl
    }
  }

  /**
   * Create an external interface port by externalizing the given (already configured) IP pin.
   * This clones the pin's live signal widths into the port - see [[ExternalizedIntfPort]].
   * The port name is set via -name directly (make_bd_intf_pins_external does not reliably
   * return the created port object across Vivado versions). Emitted as a single multi-line
   * command so the statements stay together when the connect commands are sorted.
   */
  private def externalizePin(port: BdIntfPortBase, pin: BdPinPort): TCLCommand = {
    if (pin.vivadoKind != VivadoHandleKind.IntfPin) {
      throw VivadoDesignException(s"ExternalizedIntfPort ${port.instanceName} must be connected to exactly one interface pin, but got ${pin.ref} (${pin.vivadoKind}).")
    }
    s"""make_bd_intf_pins_external -name ${port.instanceName} ${vivadoGetExpr(pin)}
       |set ${port.instanceName} [get_bd_intf_ports ${port.instanceName}]""".stripMargin.tcl
  }
}