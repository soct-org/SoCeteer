package soct.system.vivado.abstracts

/**
 * Marker trait for reset providers
 */
trait ResetSource extends Source


/**
 * Marker trait for active low reset sinks
 */
trait ResetN


/**
 * Marker trait for active high reset sinks
 */
trait Reset


/**
 * Trait for components that want automatic reset port registration
 */
trait ReceivesReset {
  /**
   * The active low reset ports for this component, to be connected to the reset provider of the clock domain if available
   */
  def resetNInPorts: () => Seq[BdPinPort]

  /**
   * The active high reset ports for this component, to be connected to the reset provider of the clock domain if available
   */
  def resetInPorts: () => Seq[BdPinPort]
}

/**
 * Trait for components that provide automatic reset connections
 */
trait ProvidesAutoReset extends ProvidesAutoDomain[ResetSource] {
}
