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

trait FPGAPort extends BdComp {
  val portName: String
  // TODO the port should be able to receive connections
}


/**
 * Case class representing a DDR4 port on the FPGA board.
 */
final class DDR4Port(override val portName: String)(implicit bd: SOCTBdBuilder, p: Parameters) extends FPGAPort


/**
 * Case class representing a reset signal provided on the FPGA board
 *
 * @param portName The name of the reset port provided by the board, usable in e.g. RESET_BOARD_INTERFACE
 */
final class FPGAResetPort(override val portName: String)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends FPGAPort with Reset

/**
 * Case class representing a port that provides a clock domain on the FPGA board
 *
 * @param freqMHz The frequency of the clock domain in MHz
 * @param reset   Optional reset provider that is synced to this clock domain
 */
final class FPGAClockDomain(val portName: String, override val freqMHz: Double, override val reset: Option[FPGAResetPort] = None)
                           (implicit bd: SOCTBdBuilder, p: Parameters) extends ClockDomain(freqMHz, reset) {
}


abstract case class FPGA() extends IsXilinxIP with HasFriendlyName {

  /**
   * The Xilinx part number for this FPGA board - e.g., "xczu7ev-ffvc1156-2-e"
   */
  val xilinxPart: String

  /**
   * The fastest clock domains provided by this FPGA board
   */
  def fastestClock()(implicit bd: SOCTBdBuilder, p: Parameters): FPGAClockDomain

  /**
   * The DDR4 ports provided by this FPGA board
   */
  def portsDDR4()(implicit bd: SOCTBdBuilder, p: Parameters): Seq[DDR4Port] = Seq.empty

  /**
   * The PMOD ports available on this FPGA board
   */
  val portsPMOD: Seq[Int] = Seq.empty

  override def toString: String = friendlyName

}