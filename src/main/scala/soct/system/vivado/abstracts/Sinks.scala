package soct.system.vivado.abstracts

import soct.system.vivado.{SOCTBdBuilder, SOCTVivado, XilinxDesignException}

import scala.annotation.{implicitNotFound, unused}
import scala.collection.{View, mutable}

@implicitNotFound(
  "Unsupported sink type: ${T}.\n" +
    "Allowed sinks are:\n" +
    "  - BdPinPort\n" +
    "  - chisel3.Data (or any subtype)\n" +
    "  - () => BdPinPort | chisel3.Data\n" +
    "  - () => Seq[BdPinPort] | Seq[chisel3.Data]"
)
sealed trait AllowedSink[T]

@unused
object AllowedSink {

  // ----- eager -----

  implicit val bdPin: AllowedSink[BdPinPort] =
    new AllowedSink[BdPinPort] {}

  implicit def dataSubtype[T <: chisel3.Data]: AllowedSink[T] =
    new AllowedSink[T] {}

  // ----- lazy single -----

  implicit def fnBdPin: AllowedSink[() => BdPinPort] =
    new AllowedSink[() => BdPinPort] {}

  implicit def fnData[T <: chisel3.Data]: AllowedSink[() => T] =
    new AllowedSink[() => T] {}

  // ----- lazy multiple -----

  implicit def fnSeqBdPin: AllowedSink[() => Seq[BdPinPort]] =
    new AllowedSink[() => Seq[BdPinPort]] {}

  implicit def fnSeqData[T <: chisel3.Data]: AllowedSink[() => Seq[T]] =
    new AllowedSink[() => Seq[T]] {}
}


/**
 * Trait for components that can collect BdPinPorts
 */
trait CollectsSinks {

  private val _sinkPins: mutable.Set[() => Seq[BdPinPort]] = mutable.Set.empty

  def sinkPins: View[BdPinPort] = _sinkPins.view.flatMap(_())

  private def toBdPins(value: Any)(implicit bd: SOCTBdBuilder): Seq[BdPinPort] =
    value match {
      case p: BdPinPort =>
        Seq(p)

      case d: chisel3.Data =>
        Seq(SOCTVivado.portToBdPin(d))

      case s: Seq[_] =>
        s.map {
          case p: BdPinPort => p
          case d: chisel3.Data => SOCTVivado.portToBdPin(d)
          case other =>
            throw new IllegalArgumentException(
              s"Unsupported sink element: ${other.getClass}"
            )
        }

      case other =>
        throw new IllegalArgumentException(
          s"Unsupported sink value: ${other.getClass}"
        )
    }

  /**
   * Add a sink to this component. The sink can be:
   * - A BdPinPort
   * - A chisel3.Data
   * - A function that returns either of the above, or a sequence of either of these
   *
   * @param sink The sink to add (lazy)
   * @tparam T The sink type (inferred)
   * @throws IllegalArgumentException if the sink type is unsupported
   * @return
   */
  @throws[IllegalArgumentException]
  def outputTo[T](sink: => T)(
    implicit
    allowed: AllowedSink[T],
    bd: SOCTBdBuilder
  ): Boolean = {

    val fn: () => Seq[BdPinPort] = () =>
      sink match {
        case f: Function0[_] => toBdPins(f())
        case other => toBdPins(other)
      }

    _sinkPins += fn
    true
  }
}

/**
 * Trait for components that have sink pins
 */
trait HasSinkPins {

  protected val sourcePins = mutable.Set[SourceForSinks]()

  /**
   * Get the BdPinPort corresponding to the given source, if any.
   *
   * @param source  The source to look up
   * @param sinkKey The key for the sink
   * @return Some(BdPinPort) if found, None otherwise
   */
  protected def getPinImpl(source: SourceForSinks, sinkKey: KeyForSink): Option[BdPinPort]

  /**
   * Get the BdPinPort corresponding to the given source.
   *
   * @param source  The source to look up
   * @param sinkKey The key for the sink
   * @return The corresponding BdPinPort
   * @throws XilinxDesignException if no BdPinPort is found for the source
   */
  @throws[XilinxDesignException]
  final def getPin(source: SourceForSinks, sinkKey: KeyForSink = NoneKeyForSink): () => BdPinPort = {
    val sinkOpt = getPinImpl(source, sinkKey)
    if (sinkOpt.isEmpty) {
      throw XilinxDesignException(s"No BdPinPort found for source $source with sink key $sinkKey in component $this")
    }
    sourcePins += source
    () => sinkOpt.get
  }
}

/**
 * Marker class to request a specific pin as a sink
 */
trait KeyForSink {
  /**
   * Get the BdPinPort for the given component
   *
   * @param comp The component
   * @tparam T The component type
   * @return Function that returns the BdPinPort
   */
  def getPin[T <: BdComp](comp: T): () => BdPinPort
}

object NoneKeyForSink extends KeyForSink {
  override def getPin[T <: BdComp](comp: T): () => BdPinPort = () => throw XilinxDesignException("NoneKeyForSink does not provide a pin")
}

/**
 * Trait for components that have a single input BdPinPort - simplifies several use cases
 */
trait HasSingleInput {

  /**
   * Get the single input BdPinPort received by this component
   */
  def input: BdPinPort
}


/**
 * Trait for components that want automatic connection to clock and reset inputs
 */
trait AutoConnect extends ReceivesClock with ReceivesReset