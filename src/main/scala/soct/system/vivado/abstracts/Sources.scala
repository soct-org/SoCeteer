package soct.system.vivado.abstracts

import soct.system.vivado.{TCLCommands, XilinxDesignException}

import scala.collection.mutable

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



