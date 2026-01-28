package soct.system.vivado.abstracts

/**
 * Marker trait for reset providers
 */
trait ResetType extends CollectsSinks

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
  def resetNInPorts: Seq[BdPinPort] = Seq.empty

  /**
   * The active high reset ports for this component, to be connected to the reset provider of the clock domain if available
   */
  def resetInPorts: Seq[BdPinPort] = Seq.empty
}

/**
 * Trait for components that provide automatic reset connections
 */
trait ProvidesAutoReset extends ProvidesAutoDomain[ResetType] {
  this: SourceForSinks =>
}
