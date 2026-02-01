package soct.system.vivado.abstracts

import soct.system.vivado.SOCTBdBuilder
import scala.annotation.implicitNotFound

/**
 * Type class for automatic bidirectional connect (<->)
 *
 * C = left-hand side type
 * T = right-hand side type
 */
@implicitNotFound(
  "No AutoConnect instance found for:\n" +
    "  ${C}  <->  ${T}\n\n" +
    "Meaning: there is no rule that allows bidirectional connection between these endpoint types.\n\n" +
    "Fix:\n" +
    "  - Add an implicit AutoConnect[${C}, ${T}] in scope, or\n" +
    "  - Use a directional operator (--> or <--), if the connection is directional."
)
trait AutoConnect[-C, -T] {
  def apply(ths: C, that: T, bd: SOCTBdBuilder): Unit
}

/**
 * Type class for directional connect (-->), i.e. source drives sink
 */
@implicitNotFound(
  "No ToSinkConnect instance found for:\n" +
    "  ${C}  -->  ${T}\n\n" +
    "Meaning: the left endpoint is not allowed to drive the right endpoint.\n\n" +
    "Fix:\n" +
    "  - Ensure the left endpoint is a driver (e.g. DrivesNet / BdPinOut / BdVirtualPortI), and\n" +
    "    the right endpoint is a sink (e.g. DrivenByNet / BdPinIn / BdVirtualPortO), or\n" +
    "  - Use '<--' if you meant the reverse direction, or\n" +
    "  - Use '<->' for directionless/interface connections, or\n" +
    "  - Add an implicit ToSinkConnect[${C}, ${T}] in scope."
)
trait ToSinkConnect[-C, -T] {
  def apply(source: C, sink: T, bd: SOCTBdBuilder): Unit
}

/**
 * Type class for directional connect (<--), i.e. sink is driven by source
 */
@implicitNotFound(
  "No ToSourceConnect instance found for:\n" +
    "  ${C}  <--  ${T}\n\n" +
    "Meaning: the right endpoint is not allowed to drive the left endpoint.\n\n" +
    "Fix:\n" +
    "  - Ensure the right endpoint is a driver (e.g. DrivesNet / BdPinOut / BdVirtualPortI), and\n" +
    "    the left endpoint is a sink (e.g. DrivenByNet / BdPinIn / BdVirtualPortO), or\n" +
    "  - Use '-->' if you meant the opposite direction, or\n" +
    "  - Use '<->' for directionless/interface connections, or\n" +
    "  - Add an implicit ToSourceConnect[${C}, ${T}] in scope."
)
trait ToSourceConnect[-C, -T] {
  def apply(sink: C, source: T, bd: SOCTBdBuilder): Unit
}

trait ConnectOps { this: AnyRef =>
  final def -->[T](sink: T)(implicit ev: ToSinkConnect[this.type, T], bd: SOCTBdBuilder): Unit =
    ev(this, sink, bd)

  final def <--[T](source: T)(implicit ev: ToSourceConnect[this.type, T], bd: SOCTBdBuilder): Unit =
    ev(this, source, bd)

  final def <->[T](that: T)(implicit ev: AutoConnect[this.type, T], bd: SOCTBdBuilder): Unit =
    ev(this, that, bd)
}
