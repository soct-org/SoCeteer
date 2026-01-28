package soct.system.vivado.abstracts

import soct.system.vivado.SOCTBdBuilder

/**
 * Trait for components that receive clock inputs
 */
trait ReceivesClock {

  /**
   * The clock input ports for this component, to be connected to the clock domain if available
   */
  def clockInPorts: Seq[BdPinPort] = Seq.empty
}

/**
 * Trait for components that provide automatic clock connections
 */
trait ProvidesAutoClock extends ProvidesAutoDomain[ClockDomain] {
  this: SourceForSinks =>

}

/**
 * Case class representing a clock domain in the design
 *
 * @param freqMHz    The frequency of the clock domain in MHz
 * @param reset      Optional reset provider that is synced to this clock domain
 * @param tclVarName Optional name of the dereferenced TCL variable representing this clock domain in the block design, e.g, "$clock_freq"
 */
case class ClockDomain(freqMHz: Double,
                       var reset: Option[ResetType] = None,
                       tclVarName: Option[String] = None)
                      (implicit bd: SOCTBdBuilder) extends CollectsSinks {
  if (tclVarName.isDefined) {
    bd.addBdVar(tclVarName.get, "The core clock frequency in MHz", freqMHz.toString)
  }
}

/**
 * Helper object to instantiate components within a given clock domain
 */
object WithDomain {

  /**
   * Instantiate a block within the given clock domain
   *
   * @param cd    The clock domain
   * @param block The block to instantiate
   * @tparam T The return type of the block
   * @return The result of the block
   */
  def apply[T, C <: ClockDomain](cd: C)(
    block: Option[C] => T
  ): T = {
    block(Some(cd))
  }
}


