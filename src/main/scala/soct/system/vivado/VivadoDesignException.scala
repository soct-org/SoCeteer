package soct.system.vivado

/**
 * The single error type deliberately raised by the Vivado backend.
 *
 * It signals invalid block-design construction, inconsistent board definitions, TCL/XDC
 * emission problems, and failures while driving Vivado itself. Code in `soct.system.vivado`
 * (and the Vivado-specific paths of the launcher utilities) reports every deliberate error
 * through this type; incidental runtime exceptions (e.g. `NullPointerException`) are not
 * wrapped.
 *
 * @param message description of the design error
 * @param cause   underlying exception, if this error wraps one
 */
class VivadoDesignException(message: String = "", cause: Throwable = None.orNull)
  extends Exception(message, cause)

object VivadoDesignException {

  /**
   * Create an exception with a message only.
   *
   * @param message description of the design error
   * @return the new exception (to be thrown by the caller)
   */
  def apply(message: String): VivadoDesignException = new VivadoDesignException(message)

  /**
   * Create an exception that wraps an underlying cause.
   *
   * @param message description of the design error
   * @param cause   the underlying exception
   * @return the new exception (to be thrown by the caller)
   */
  def apply(message: String, cause: Throwable): VivadoDesignException = new VivadoDesignException(message, cause)
}
