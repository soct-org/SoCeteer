package soct.system.vivado.abstracts

/**
 * Marker trait for reset providers
 */
trait ProvidesReset


/**
 * Marker trait for active-low reset providers
 */
trait ResetN extends ProvidesReset


/**
 * Marker trait for active-high reset providers
 */
trait Reset extends ProvidesReset
