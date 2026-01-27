package soct.system.vivado.components

import soct.system.vivado.XilinxDesignException

/**
 * Marker trait for reset providers
 */
trait ResetType extends CollectsPins

/**
 * Reset type representing an active-high reset
 */
trait Reset extends ResetType

/**
 * Reset type representing an active-low reset
 */
trait ResetN extends ResetType


/**
 * Trait for components that want automatic reset port registration
 */
trait ReceivesReset {
  /**
   * The active low reset ports for this component, to be connected to the reset provider of the clock domain if available
   */
  def resetNInPorts: Seq[BdPinBase] = Seq.empty

  /**
   * The active high reset ports for this component, to be connected to the reset provider of the clock domain if available
   */
  def resetInPorts: Seq[BdPinBase] = Seq.empty
}


/**
 * Trait for components that provide automatic reset connections
 */
trait ProvidesAutoReset {
  this: InstantiableBdComp =>

  /**
   * The reset providers provided by this component - Ensure proper ordering if multiple resets are present!
   */
  val resets: Seq[ResetType]


  /**
   * Implementation method to get the reset output port for a given reset type and sink pin
   *
   * @param reset    The reset type
   * @param resetIdx The index of the reset in the resets sequence
   * @param sinkPin  The sink pin to connect to
   * @param pinIdx   The index of the sink pin in the sinkPins sequence
   * @throws XilinxDesignException if a reset output port cannot be found for a given reset type and sink pin
   * @return The source BdPin to connect to the sink pin
   */
  @throws[XilinxDesignException]
  protected def resetOutPortImpl(reset: ResetType, resetIdx: Int, sinkPin: BdPinBase, pinIdx: Int): BdPin


  /**
   * TCL commands to connect the reset output ports to the sink pins
   *
   * @return The sequence of TCL commands
   */
  @throws[XilinxDesignException]
  def resetTclCommands: Seq[String] = {
    for {
      (reset, resetIdx) <- resets.zipWithIndex
      (sinkPin, pinIdx) <- reset.sinkPins.zipWithIndex
      sourcePin = resetOutPortImpl(reset, resetIdx, sinkPin, pinIdx)
    } yield s"connect_bd_net [get_bd_pins $sourcePin] [get_bd_pins $sinkPin]"
  }
}
