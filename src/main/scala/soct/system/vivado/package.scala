package soct.system

package object vivado {


  def hasMultiMemSupport(className: String): Boolean = {
    // TODO: Implement logic to check if the className has multi-memory support
    // This is a placeholder implementation and should be replaced with actual logic
    className.contains(classOf[SOCTVivadoSystemMultiMem].getSimpleName)
  }


  /**
   * TCL commands container
   *
   * @param command Sequence of TCL commands
   */
  case class TCLCommand(command: String) {
    override def toString: String = command
  }

  // Convenience converter from String to TCLCommand
  implicit class StringToTCLCommand(s: String) {
    def tcl: TCLCommand = TCLCommand(s)
  }

  // Provide an implicit Ordering so Seq[TCLCommand].sorted compiles
  implicit val tclCommandOrdering: Ordering[TCLCommand] = Ordering.by(_.command)

  /**
   * TCL commands sequence type
   */
  type TCLCommands = Seq[TCLCommand]
}
