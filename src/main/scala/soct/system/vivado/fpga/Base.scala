package soct.system.vivado.fpga

import soct.system.vivado.components.{ClockDomain, HasFriendlyName, IsXilinxIP, Reset}


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
 * Case class representing a DDR4 port on the FPGA board.
 *
 * @param ddr4Port     The name of the DDR4 port
 */
case class DDR4Port(ddr4Port: String)


/**
 * Case class representing a reset signal provided on the FPGA board
 * @param name The name of the reset port provided by the board, usable in e.g. RESET_BOARD_INTERFACE
 */
final class FPGAReset(override val name: String) extends Reset(name)

/**
 * Case class representing a port that provides a clock domain on the FPGA board
 *
 * @param name    The name of the clock port provided board, usable in e.g. C0_CLOCK_BOARD_INTERFACE
 * @param freqMHz The frequency of the clock domain in MHz
 * @param reset   Optional reset provider that is synced to this clock domain
 */
final class FPGAClockDomain(override val name: String, override val freqMHz: Double, override val reset: Option[FPGAReset] = None)
  extends ClockDomain(name, freqMHz, reset)


abstract case class FPGA() extends IsXilinxIP with HasFriendlyName {
  val xilinxPart: String

  /**
   * The clock domains provided by this FPGA board - must be sorted by frequency descending - highest first
   */
  val clocks: Seq[FPGAClockDomain] = Seq.empty

  val portsDDR4: Seq[DDR4Port] = Seq.empty

  val portsPMOD: Seq[Int] = Seq.empty

  override def toString: String = friendlyName

}