package soct.system.vivado.abstracts

import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}

/**
 * Type class for automatic assignment of components
 *
 * @tparam C The type
 * @tparam T The type to assign from
 */
trait AutoConnect[-C, -T] {
  def apply(ths: C, that: T, bd: SOCTBdBuilder): Unit
}

/**
 * Type class for automatic assignment of components to sinks
 *
 * @tparam C The component type
 * @tparam T The sink type
 */
trait ToSinkConnect[-C, -T] {
  def apply(source: C, sink: T, bd: SOCTBdBuilder): Unit
}


/**
 * Type class for automatic assignment of components from sources
 *
 * @tparam C The component type
 * @tparam T The source type
 */
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