package soct.system

package object vivado {
  /**
   * TCL commands container
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

  type TCLCommands = Seq[TCLCommand]
}
