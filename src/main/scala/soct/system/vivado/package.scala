package soct.system

package object vivado {


  /**
   * Whether the given top module supports multiple memory channels, i.e. mixes in the
   * [[SupportsMultiMem]] capability marker (directly or via a superclass).
   *
   * @param top the top module class selected by the launcher (Module or LazyModule side)
   * @return true if the top module supports multiple memory channels
   */
  def hasMultiMemSupport(top: soct.ChiselTop): Boolean = {
    classOf[SupportsMultiMem].isAssignableFrom(top.merge)
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
