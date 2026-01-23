package soct.system.vivado.fpga

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.components.{BdComp, ClockDomain, HasConnections, HasFriendlyName, IsXilinxIP, Reset}

import scala.annotation.unused


/**
 * Registry for known FPGA boards.
 */
object FPGARegistry {

  // TODO ADD YOUR BOARD HERE! - Use uppercase names as keys
  private val registry: Map[String, Class[_ <: FPGA]] = Map(
    "ZCU104" -> classOf[ZCU104]
  )

  def isKnownBoard(name: String): Boolean = registry.contains(name)

  def getKnownBoards: Seq[String] = registry.keys.toSeq

  /** name -> Board (throws if not found) */
  def n2b(name: String): Class[_ <: FPGA] = {
    registry.getOrElse(name.toUpperCase, throw new Exception(s"Unknown FPGA board: $name"))
  }

  /** name -> Board */
  def n2bOpt(name: String): Option[Class[_ <: FPGA]] = {
    registry.get(name.toUpperCase)
  }

  /** Board -> name (throws if not found) */
  def b2n(clazz: Class[_ <: FPGA]): String = {
    registry.find(_._2 == clazz) match {
      case Some((name, _)) => name
      case None => throw new Exception(s"FPGA class ${clazz.getName} not found in registry")
    }
  }

  /** Board -> name */
  def b2nOpt(clazz: Class[_ <: FPGA]): Option[String] = {
    registry.find(_._2 == clazz) match {
      case Some((name, _)) => Some(name)
      case None => None
    }
  }

  /**
   * Instantiate an FPGA board given its class.
   * @param clazz The class of the FPGA board to instantiate
   * @return An instance of the FPGA board
   */
  def resolveBoardInstance(clazz: Class[_ <: FPGA])(implicit bd: SOCTBdBuilder, p: Parameters): FPGA = {
    clazz.getConstructor(classOf[SOCTBdBuilder], classOf[Parameters]).newInstance(bd, p)
  }
}

/**
 * Abstract base class for FPGA ports provided by the board.
 */
abstract class FPGAPort(implicit bd: SOCTBdBuilder, p: Parameters) extends BdComp {
  val portName: String
}


/**
 * Case class representing a DDR4 port on the FPGA board.
 */
case class DDR4Port(override val portName: String)(implicit bd: SOCTBdBuilder, p: Parameters) extends FPGAPort


/**
 * Case class representing a reset signal provided on the FPGA board
 *
 * @param portName The name of the reset port provided by the board, usable in e.g. RESET_BOARD_INTERFACE
 */
case class FPGAResetPort(override val portName: String)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends FPGAPort with Reset with HasConnections {

  override def connectTclCommands: Seq[String] = {
    receiverPorts.flatMap(_._2()).map { port =>
      s"connect_bd_net [get_bd_ports $portName] [get_bd_pins $port]"
    }.toSeq
  }
}


case class FPGAClockPort(override val portName: String, freqMHz: Double)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends FPGAPort

/**
 * Class representing a port that provides a clock domain on the FPGA board
 *
 * @param port    The clock port provided by the board
 * @param reset   Optional reset provider that is synced to this clock domain
 */
final class FPGAClockDomain(val port: FPGAClockPort, override val reset: Option[FPGAResetPort] = None)
                           (implicit bd: SOCTBdBuilder) extends ClockDomain(port.freqMHz, reset) {
}

/**
 * Abstract base class for FPGA boards. Subclasses must provide information about the specific FPGA board,
 * such as the Xilinx part number, available clock domains, DDR4 ports, and PMOD ports.
 * For instance, see the ZCU104 implementation.
 * Subclasses must provide a SOCTBdBuilder and Parameters context for instantiation.
 */
abstract class FPGA(implicit @unused bd: SOCTBdBuilder, @unused p: Parameters) extends IsXilinxIP with HasFriendlyName {

  /**
   * The Xilinx part number for this FPGA board - e.g., "xczu7ev-ffvc1156-2-e"
   */
  val xilinxPart: String

  /**
   * The fastest clock domains provided by this FPGA board
   */
  def fastestClock(): FPGAClockDomain

  /**
   * The DDR4 ports provided by this FPGA board
   */
  def portsDDR4(): Seq[DDR4Port] = Seq.empty

  /**
   * The PMOD ports available on this FPGA board
   */
  val portsPMOD: Seq[Int] = Seq.empty

  override def toString: String = friendlyName

}