package soct.system.vivado.components

import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}

/**
 * Trait for components that receive clock inputs
 */
trait ReceivesClock {

  /**
   * The clock input ports for this component, to be connected to the clock domain if available
   */
  def clockInPorts: Seq[BdPinBase] = Seq.empty
}

/**
 * Trait for components that provide automatic clock connections
 */
trait ProvidesAutoClock {
  this: InstantiableBdComp =>

  /**
   * The clock domains provided by this component - Ensure proper ordering if multiple clock domains are present!
   */
  val cds: Seq[ClockDomain]

  /**
   * Implementation method to get the clock output port for a given clock domain and sink pin
   *
   * @param cd      The clock domain
   * @param domIdx  The index of the clock domain in the cds sequence
   * @param sinkPin The sink pin to connect to
   * @param pinIdx  The index of the sink pin in the sinkPins sequence
   * @throws XilinxDesignException if a clock output port cannot be found for a given clock domain and sink pin
   * @return The source BdPinBase to connect to the sink pin
   */
  @throws[XilinxDesignException]
  protected def clockOutPortImpl(cd: ClockDomain, domIdx: Int, sinkPin: BdPinBase, pinIdx: Int): BdPinBase

  /**
   * TCL commands to connect the clock output ports to the sink pins
   *
   * @throws XilinxDesignException if a clock output port cannot be found for a given clock domain and sink pin
   * @return The sequence of TCL commands
   */
  @throws[XilinxDesignException]
  def clkTclCommands: Seq[String] = {
    for {
      (cd, domIdx) <- cds.zipWithIndex
      (sink, pinIdx) <- cd.sinkPins.zipWithIndex
      source = clockOutPortImpl(cd, domIdx, sink, pinIdx)
    } yield {
      BdPinBase.connect(source, sink)
    }
  }
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
                      (implicit bd: SOCTBdBuilder) extends CollectsPins {
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


