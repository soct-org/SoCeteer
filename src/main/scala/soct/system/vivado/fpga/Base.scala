package soct.system.vivado.fpga

import soct.system.vivado.components.{HasFriendlyName, IsXilinxIP}


/**
 * Registry for known FPGA boards.
 */
object FPGARegistry {

  /**
   * Resolve a board by name
   *
   * @param name Name of the board
   * @return Some(FPGA) if found, None otherwise
   */
  def resolve(name: String): Option[FPGA] = {
    val comp = name.toLowerCase

    if (comp == ZCU104.friendlyName.toLowerCase) {
      Some(ZCU104)
    } else {
      None
    }
  }

  /**
   * List of available boards
   */
  def availableBoards: Seq[String] = {
    Seq(
      ZCU104.friendlyName
    )
  }
}



abstract case class FPGA() extends IsXilinxIP with HasFriendlyName
{
  val xilinxPart: String

  val portsDDR4: Seq[Int] = Seq.empty

  val portsPMOD: Seq[Int] = Seq.empty

  override def toString: String = friendlyName

}