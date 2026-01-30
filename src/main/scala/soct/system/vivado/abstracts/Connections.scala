package soct.system.vivado.abstracts

import soct.system.vivado.{SOCTBdBuilder, SOCTVivado, TCLCommands, XilinxDesignException}

import scala.collection.{View, mutable}

/**
 * Generic trait for automatic domain-based connections (clock, reset, etc.)
 */
trait ProvidesAutoDomain[D <: CollectsSinks] {
  this: SourceForSinks =>

  /** Ordered domains provided by this component */
  protected val domains: Seq[D]

  /**
   * Get the output BdPinPort for the given domain and sink pin.
   *
   * @param domain    The domain
   * @param domainIdx The index of the domain
   * @param sinkPin   The sink BdPinPort
   * @param pinIdx    The index of the sink pin within the domain
   * @return The corresponding output BdPinPort
   */
  @throws[XilinxDesignException]
  protected def outPortImpl(domain: D, domainIdx: Int, sinkPin: BdPinPort, pinIdx: Int): BdPinPort

  // Add connection commands for each domain/sink pair
  otherConnects.addOne(() => for {
    (domain, domIdx) <- domains.zipWithIndex
    (sink, pinIdx) <- domain.sinkPins.zipWithIndex
    source = outPortImpl(domain, domIdx, sink, pinIdx)
  } yield BdPinPort.connect1(source, sink))
}

trait SourceForSinks extends CollectsSinks {

  /**
   * Emit the TCL commands to connect this component in the design
   */
  protected def connectToSinksImpl: TCLCommands

  /**
   * Children classes can add extra connection commands here that are later appended to the main connection commands.
   */
  protected var otherConnects: mutable.ListBuffer[() => TCLCommands] = mutable.ListBuffer.empty

  /**
   * Get the TCL commands to connect this component in the design
   */
  def getCommands: TCLCommands = {
    connectToSinksImpl ++ otherConnects.flatMap(fn => fn()).toSeq
  }
}

/**
 * Trait for components that have a single output BdPinPort - simplifies several use cases
 */
trait HasSingleOutput {

  /**
   * Get the single output BdPinPort provided by this component
   */
  def output: BdPinPort
}

trait HasSingleInput {

  /**
   * Get the single input BdPinPort received by this component
   */
  def input: BdPinPort
}

/**
 * Trait for components that can collect BdPinPorts
 */
trait CollectsSinks {
  protected val _sinkPins: mutable.Set[() => Seq[BdPinPort]] = mutable.Set.empty

  /**
   * Get the sink BdPinPorts that this component provides data to.
   * This evaluates all registered sink functions which can result in Chisel module calls.
   * Therefore, it should only be called after full evaluation of the Chisel design.
   *
   * @return A view of the sink BdPinPorts
   */
  def sinkPins: View[BdPinPort] = _sinkPins.view.flatMap(fn => fn())

  /**
   * Lazily register Multiple sink BdPinPorts provided by this component.
   *
   * @param sinks Function that returns the sequence of sink BdPinPorts
   * @return Whether the registration was successful
   */
  def outputToLM(sinks: () => Seq[BdPinPort]): Boolean = {
    _sinkPins += sinks
    true
  }

  /**
   * Lazily register Multiple sink chisel3.Data ports provided by this component.
   *
   * @param sinks Function that returns the sequence of sink chisel3.Data ports
   * @param bd    The block design builder context
   * @return Whether the registration was successful
   */
  def outputToLM(sinks: () => Seq[chisel3.Data])(implicit bd: SOCTBdBuilder): Boolean = {
    _sinkPins += (() => sinks().map(SOCTVivado.portToBdPin))
    true
  }

  /**
   * Lazily register a single sink BdPinPort provided by this component.
   *
   * @param sink Function that returns the sink BdPinPort
   * @return Whether the registration was successful
   */
  def outputToL(sink: () => BdPinPort): Boolean = {
    _sinkPins += (() => Seq(sink()))
    true
  }

  /**
   * Lazily register a single sink chisel3.Data port provided by this component.
   *
   * @param sink Function that returns the sink chisel3.Data port
   * @param bd   The block design builder context
   * @return Whether the registration was successful
   */
  def outputToL(sink: () => chisel3.Data)(implicit bd: SOCTBdBuilder): Boolean = {
    _sinkPins += (() => Seq(SOCTVivado.portToBdPin(sink())))
    true
  }

  /**
   * Register a single sink BdPinPort provided by this component.
   *
   * @param sink The sink BdPinPort
   * @return Whether the registration was successful
   */
  def outputTo(sink: => BdPinPort): Boolean = {
    _sinkPins += (() => Seq(sink))
    true
  }

  /**
   * Register a single sink chisel3.Data port provided by this component.
   *
   * @param sink The sink chisel3.Data port
   * @param bd   The block design builder context
   * @return Whether the registration was successful
   */
  def outputTo(sink: => chisel3.Data)(implicit bd: SOCTBdBuilder): Boolean = {
    _sinkPins += (() => Seq(SOCTVivado.portToBdPin(sink)))
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
 * Trait for components that want automatic connection to clock and reset inputs
 */
trait AutoConnect extends ReceivesClock with ReceivesReset
