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

trait ConnectOps {
  this: AnyRef =>

  /**
   * Connect this to the sink. Use when this is a source and the sink is a driver.
   *
   * @param sink The sink to connect to
   * @param ev   Implicit ToSinkConnect evidence that this can drive the sink, and that the sink can be driven by this
   * @param bd   Implicit board builder for adding connections
   * @tparam T Type of the sink
   */
  final def -->[T](sink: T)(implicit ev: ToSinkConnect[this.type, T], bd: SOCTBdBuilder): Unit =
    ev(this, sink, bd)

  /**
   * Connect this to each element of the sink collection. Useful for connecting a single source to multiple sinks.
   *
   * @param sink Collection of sinks to connect to
   * @param ev   Implicit ToSinkConnect evidence that this can drive each element of the sink collection, and that each element of the sink collection can be driven by this
   * @param bd   Implicit board builder for adding connections
   * @tparam T Type of the sink elements
   */
  final def --*>[T](sink: Iterable[T])(implicit ev: ToSinkConnect[this.type, T], bd: SOCTBdBuilder): Unit =
    sink.foreach(s => ev(this, s, bd))

  /**
   * Connect this to the source. Use when this is a sink and the source is a driver.
   *
   * @param source The source to connect from
   * @param ev     Implicit ToSourceConnect evidence that this can be driven by the source, and that the source can drive this
   * @param bd     Implicit board builder for adding connections
   * @tparam T Type of the source
   */
  final def <--[T](source: T)(implicit ev: ToSourceConnect[this.type, T], bd: SOCTBdBuilder): Unit =
    ev(this, source, bd)

  /**
   * Connect this to each element of the source collection. Useful for connecting multiple sources to a single sink.
   *
   * @param source Collection of sources to connect from
   * @param ev     Implicit ToSourceConnect evidence that each element of the source collection can drive this, and that this can be driven by each element of the source collection
   * @param bd     Implicit board builder for adding connections
   * @tparam T Type of the source elements
   */
  final def <--*[T](source: Iterable[T])(implicit ev: ToSourceConnect[this.type, T], bd: SOCTBdBuilder): Unit =
    source.foreach(s => ev(this, s, bd))


  /**
   * Bidirectional connect. Use when the connection is directionless (e.g. connecting two interfaces together), or when the directionality is handled by the AutoConnect instance itself (e.g. connecting a master interface to a slave interface, where the AutoConnect instance knows how to connect the appropriate signals in each direction).
   *
   * @param that The other endpoint to connect to
   * @param ev   Implicit AutoConnect evidence that this can be connected to that, and that that can be connected to this
   * @param bd   Implicit board builder for adding connections
   * @tparam T Type of the other endpoint
   */
  final def <->[T](that: T)(implicit ev: AutoConnect[this.type, T], bd: SOCTBdBuilder): Unit =
    ev(this, that, bd)



  /**
   * Bidirectional connect to each element of the other endpoint collection. Useful for connecting collections of interfaces together in a directionless manner, or when the directionality is handled by the AutoConnect instance itself.
   *
   * @param that Collection of other endpoints to connect to
   * @param ev   Implicit AutoConnect evidence that this can be connected to each element of that, and that each element of that can be connected to this
   * @param bd   Implicit board builder for adding connections
   * @tparam T Type of the other endpoint elements
   */
  final def <->*[T](that: Iterable[T])(implicit ev: AutoConnect[this.type, T], bd: SOCTBdBuilder): Unit =
    that.foreach(t => ev(this, t, bd))
}
