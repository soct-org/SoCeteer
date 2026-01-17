package soct.system.vivado.fpga

import soct.system.vivado.components.{ClockDomain, HasFriendlyName, IsXilinxIP}


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

/**
 * Case class representing a DDR4 port on the FPGA board. While many boards allow custom wiring of DDR4,
 * this class captures the default clock and reset signals associated with the DDR4 port.
 * @param ddr4Port The name of the DDR4 port
 * @param defaultClock The default clock domain for the DDR4 port
 * @param defaultReset The default reset signal for the DDR4 port
 */
case class DDR4Port(ddr4Port: String, defaultClock: ClockDomain, defaultReset: String)


abstract case class FPGA() extends IsXilinxIP with HasFriendlyName
{
  val xilinxPart: String

  val portsDDR4: Seq[DDR4Port] = Seq.empty

  val portsPMOD: Seq[Int] = Seq.empty

  override def toString: String = friendlyName

}