package soct.system.vivado.abstracts

import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}

/**
 * Type class for automatic assignment of components
 *
 * @tparam C The component type
 * @tparam T The type to assign from
 */
trait AutoConnect[C <: BdComp, T] {
  def apply(comp: C, that: T, bd: SOCTBdBuilder): Unit
}

/**
 * Type class for automatic assignment of components to sinks
 *
 * @tparam C The component type
 * @tparam T The sink type
 */
trait ToSinkConnect[C <: BdComp, T] {
  def apply(comp: C, sink: T, bd: SOCTBdBuilder): Unit
}


/**
 * Type class for automatic assignment of components from sources
 *
 * @tparam C The component type
 * @tparam T The source type
 */
trait ToSourceConnect[C <: BdComp, T]{
  def apply(comp: C, source: T, bd: SOCTBdBuilder): Unit = {}
}

/**
 * Trait for components that support automatic assignment
 *
 * @tparam C The component type
 */
trait HasConnect[C <: BdComp] {
  self: C =>

  /**
   * Indicate that this component's source should be connected to the given sink
   * @param sink The sink to connect to
   */
  final def -->[T](sink: T)(implicit ev: ToSinkConnect[C, T], bd: SOCTBdBuilder): Unit = {
    ev(self, sink, bd)
  }

  /**
   * Indicate that this component's sink should be connected from the given source
   * @param source The source to connect from
   */
  final def <--[T](source: T)(implicit ev: ToSourceConnect[C, T], bd: SOCTBdBuilder): Unit = {
    ev(self, source, bd)
  }

  /**
   * Indicate that this component's source should be connected from the given source
   * @param that The source to connect from
   */
  final def <-> [T](that: T)(implicit ev: AutoConnect[C, T], bd: SOCTBdBuilder): Unit = {
    ev(self, that, bd)
  }
}


/**
 * Trait for components that want automatic connection to clock and reset inputs
 */
trait AutoClockAndReset extends ReceivesClock with ReceivesReset