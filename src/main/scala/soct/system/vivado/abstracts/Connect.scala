package soct.system.vivado.abstracts

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTVivado.portToBdPin
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}

import scala.collection.{View, mutable}

/**
 * Type class for automatic assignment of components
 *
 * @tparam C The component type
 * @tparam T The type to assign from
 */
trait AutoConnect[C <: BdComp, T] {
  def apply(source: C, sink: T): Unit
}

/**
 * Trait for components that support automatic assignment
 *
 * @tparam C The component type
 */
trait HasAutoConnect[C <: BdComp] {
  self: C =>
  /**
   * Indicate that this component's source should be connected to the given sink
   * @param sink The sink to connect to
   */
  final def -->[T](sink: T)(implicit ev: AutoConnect[C, T]): Unit = {
    ev(self, sink)
  }

  /**
   * Indicate that this component's source should be connected from the given source
   * @param that The source to connect from
   */
  final def <-> [T](that: T)(implicit ev: AutoConnect[C, T]): Unit = {
    ev(self, that)
  }
}


trait HasIO {
  def getIO: BdPinPort
}


abstract class SingleIO(var io: Option[HasIO] = None)(implicit bd: SOCTBdBuilder, p: Parameters) extends BdBaseComp with HasIO {
  def connect(io: HasIO): Unit = {
    if (this.io.isDefined) {
      soct.log.warn(s"Overwriting existing IO connection for $this")
    }
    this.io = Some(io)
  }
}

object SingleIO {
  def apply(data: chisel3.Data)(implicit bd: SOCTBdBuilder, p: Parameters): SingleIO = {
    new SingleIO {
      override def getIO(): BdPinPort = portToBdPin(data)
    }
  }
}



trait IsSource extends HasIO {

}

abstract class Source()(implicit bd: SOCTBdBuilder, p: Parameters) extends BdBaseComp with IsSource  {
  private val outputs: mutable.Set[HasIO] = mutable.Set.empty

  def getIOs: View[BdPinPort] = outputs.view.map(_.getIO)

  def clearIOs(): Unit = {
    outputs.clear()
  }

  def add(io: HasIO): Unit = {
    outputs += io
  }

  def add(data: chisel3.Data): Unit = {
    add(SingleIO(data))
  }

  def -->(sink: SingleIO): Unit = {
    sink.connect(this)
    outputs += sink
  }
}


trait HasSingleSink {
  val sink: SingleIO
}


trait HasSingleSource {
  val source: Source
}

/**
 * Trait for components that want automatic connection to clock and reset inputs
 */
trait AutoClockAndReset extends ReceivesClock with ReceivesReset