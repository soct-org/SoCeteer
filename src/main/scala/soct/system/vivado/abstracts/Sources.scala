package soct.system.vivado.abstracts

import soct.system.vivado.{TCLCommands, XilinxDesignException}

/**
 * Generic trait for automatic domain-based connections (clock, reset, etc.)
 */
trait ProvidesAutoDomain[D] {
  /** Ordered domains provided by this component */
  protected val domains: Seq[D]

  /**
   * Generate automatic connections for all domains and their sink pins
   */
  def autoConnects: TCLCommands = {
    for {
      (domain, domIdx) <- domains.zipWithIndex
      (sink, pinIdx) <- domain.sinkPins.zipWithIndex
      source = outPortImpl(domain, domIdx, sink, pinIdx)
    } yield BdPinPort.connect1(source, sink)
  }
}



