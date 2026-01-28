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
   * Resolve the output pin for a given domain and sink
   */
  @throws[XilinxDesignException]
  protected def outPortImpl(domain: D, domainIdx: Int, sinkPin: BdPinPort, pinIdx: Int): BdPinPort

  // Add connection commands for each domain/sink pair
  otherConnects.addOne(() => for {
    (domain, domIdx) <- domains.zipWithIndex
    (sink, pinIdx) <- domain.sinkPins.zipWithIndex
    source = outPortImpl(domain, domIdx, sink, pinIdx)
  } yield BdPinPort.connect(source, sink))
}

trait SourceForSinks extends CollectsSinks {

  /**
   * Emit the TCL commands to connect this component in the design
   */
  protected def connectToSinksImpl: TCLCommands

  /**
   * Children classes can add extra connection commands here that are later appended to the main connection commands.
   */
  protected var otherConnects : mutable.ListBuffer[() => TCLCommands] = mutable.ListBuffer.empty

  /**
   * Get the TCL commands to connect this component in the design
   */
  def getCommands: TCLCommands = {
    connectToSinksImpl ++ otherConnects.flatMap(fn => fn()).toSeq
  }
}

/**
 * Trait for components that can collect BdPinBases
 */
trait CollectsSinks {
  protected val _sinkPins: mutable.Set[BdPinPort] = mutable.Set.empty

  // public view of the collected sink pins
  def sinkPins: View[BdPinPort] = _sinkPins.view

  /**
   * Register a sink BdPinBase that this component provides data to.
   *
   * @param sink The sink BdPinBase
   * @return True if the registration was successful
   */
  def outputTo(sink: BdPinPort): Boolean = {
    _sinkPins += sink
    true
  }

  def outputTo(sink: chisel3.Data)(implicit bd: SOCTBdBuilder): Boolean = {
    _sinkPins += SOCTVivado.portToBdPin(sink)
    true
  }
}

/**
 * Trait for components that have sink pins
 */
trait HasSinkPins {

  protected val sourcePins = mutable.Set[SourceForSinks]()

  /**
   * Get the BdPinBase corresponding to the given source, if any.
   *
   * @param source The source to look up
   * @return Some(BdPinBase) if found, None otherwise
   */
  protected def getPinImpl(source: SourceForSinks): Option[BdPinPort]

  /**
   * Get the BdPinBase corresponding to the given source.
   *
   * @param source The source to look up
   * @return The corresponding BdPinBase
   * @throws XilinxDesignException if no BdPinBase is found for the source
   */
  @throws[XilinxDesignException]
  final def getPin(source: SourceForSinks): BdPinPort = {
    val sinkOpt = getPinImpl(source)
    if (sinkOpt.isEmpty) {
      throw XilinxDesignException(s"No sink pin found for source $source in component $this")
    }
    sourcePins += source
    sinkOpt.get
  }
}


/**
 * Trait for components that want automatic connection to clock and reset inputs
 */
trait AutoConnect extends ReceivesClock with ReceivesReset
