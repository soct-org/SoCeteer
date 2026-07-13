package soct.system

package object vivado {


  /**
   * Whether the given top module supports multiple memory channels.
   *
   * Heuristic: matches the class name against [[SOCTVivadoSystemMultiMem]]. A trait-based
   * capability marker on the top class would be more robust. // TODO
   *
   * @param className the (qualified or simple) class name of the top module
   * @return true if the top module is the multi-memory system
   */
  def hasMultiMemSupport(className: String): Boolean = {
    className.contains(classOf[SOCTVivadoSystemMultiMem].getSimpleName)
  }


  /**
   * A single TCL command line.
   *
   * @param command The TCL command text
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
