package soct.system.vivado.abstracts

/**
 * Marker trait for reset providers
 */
trait ProvidesReset


/**
 * Marker trait for active low reset sinks
 */
trait ResetN extends ProvidesReset


/**
 * Marker trait for active high reset sinks
 */
trait Reset extends ProvidesReset
