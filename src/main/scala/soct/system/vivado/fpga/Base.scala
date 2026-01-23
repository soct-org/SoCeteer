package soct.system.vivado.fpga

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.components.{BdComp, ClockDomain, HasFriendlyName, IsXilinxIP, Reset}


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

case class FPGAPort(portName: String)(implicit bd: SOCTBdBuilder, p: Parameters) extends BdComp {

}


/**
 * Case class representing a DDR4 port on the FPGA board.
 */
final class DDR4Port(override val portName: String)(implicit bd: SOCTBdBuilder, p: Parameters) extends FPGAPort(portName)


/**
 * Case class representing a reset signal provided on the FPGA board
 * @param portName The name of the reset port provided by the board, usable in e.g. RESET_BOARD_INTERFACE
 */
final class FPGAReset(override val portName: String)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends FPGAPort(portName) with Reset {

  override def ref: String = portName
}

/**
 * Case class representing a port that provides a clock domain on the FPGA board
 *
 * @param name    The name of the clock port provided board, usable in e.g. C0_CLOCK_BOARD_INTERFACE
 * @param freqMHz The frequency of the clock domain in MHz
 * @param reset   Optional reset provider that is synced to this clock domain
 */
final class FPGAClockDomain(override val name: String, override val freqMHz: Double, override val reset: Option[FPGAReset] = None)
                           (implicit bd: SOCTBdBuilder, p: Parameters) extends ClockDomain(name, freqMHz, reset = reset)


abstract case class FPGA() extends IsXilinxIP with HasFriendlyName {
  val xilinxPart: String

  /**
   * The fastest clock domains provided by this FPGA board
   */
  def fastestClock()(implicit bd: SOCTBdBuilder, p:Parameters): FPGAClockDomain

  def portsDDR4()(implicit bd: SOCTBdBuilder, p:Parameters): Seq[DDR4Port] = Seq.empty

  val portsPMOD: Seq[Int] = Seq.empty

  override def toString: String = friendlyName

}