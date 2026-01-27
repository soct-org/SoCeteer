package soct.system.vivado.fpga

import org.chipsalliance.cde.config.Parameters
import soct.FPGAResetPolarity
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.components.{ClockDomain, HasFriendlyName, IsXilinx, Reset, ResetN, ResetType, VirtualPort, XIntfPort}

import scala.annotation.unused


/**
 * Registry for known FPGA boards.
 */
object FPGARegistry {

  // TODO ADD YOUR BOARD HERE! - Use uppercase names as keys
  private val registry: Map[String, Class[_ <: FPGA]] = Map(
    "ZCU104" -> classOf[ZCU104]
  )

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
   *
   * @param clazz The class of the FPGA board to instantiate
   * @return An instance of the FPGA board
   */
  def resolveBoardInstance(clazz: Class[_ <: FPGA])(implicit bd: SOCTBdBuilder, p: Parameters): FPGA = {
    clazz.getConstructor(classOf[SOCTBdBuilder], classOf[Parameters]).newInstance(bd, p)
  }
}


/**
 * Case class representing a DDR4 port on the FPGA board.
 */
case class DDR4Port(override val instanceName: String)(implicit bd: SOCTBdBuilder, p: Parameters) extends XIntfPort {

  override def mode: String = "Master"

  override def partName: String = "xilinx.com:interface:ddr4_rtl:1.0"
}

/**
 * Case class representing a reset port on the FPGA board
 */
abstract class FPGAResetPortType(implicit bd: SOCTBdBuilder, p: Parameters) extends VirtualPort with ResetType {
  override def ifType: String = "rst"

  override def dir: String = "I"
}

case class FPGAResetPort(override val instanceName: String)(implicit bd: SOCTBdBuilder, p: Parameters) extends FPGAResetPortType with Reset {
  override def defaultProperties: Map[String, String] = Map(
    "POLARITY" -> "ACTIVE_HIGH"
  )
}

case class FPGAResetNPort(override val instanceName: String)(implicit bd: SOCTBdBuilder, p: Parameters) extends FPGAResetPortType with ResetN {
  override def defaultProperties: Map[String, String] = Map(
    "POLARITY" -> "ACTIVE_LOW"
  )
}

/**
 * Case class representing a clock port on the FPGA board
 *
 * @param instanceName  The name of the clock port provided by the board, usable in e.g. CLOCK_BOARD_INTERFACE
 * @param freqMHz The frequency of the clock in MHz
 */
case class FPGAClockPort(override val instanceName: String, freqMHz: Double)(implicit bd: SOCTBdBuilder, p: Parameters) extends XIntfPort  {

  override def mode: String = "Slave"

  override def partName: String = "xilinx.com:interface:diff_clock_rtl:1.0"

  override def defaultProperties: Map[String, String] = Map(
    "FREQ_HZ" -> (freqMHz * 1e6).toInt.toString
  )
}

/**
 * Class representing a port that provides a clock domain on the FPGA board
 *
 * @param port  The clock port provided by the board
 * @param reset Optional reset provider that is synced to this clock domain
 */
final class FPGAClockDomain(val port: FPGAClockPort, reset: Option[FPGAResetPortType] = None)
                           (implicit bd: SOCTBdBuilder) extends ClockDomain(port.freqMHz, reset) {
}

/**
 * Abstract base class for FPGA boards. Subclasses must provide information about the specific FPGA board,
 * such as the Xilinx part number, available clock domains, DDR4 ports, and PMOD ports.
 * For instance, see the ZCU104 implementation.
 * Subclasses must provide a SOCTBdBuilder and Parameters context for instantiation.
 */
abstract class FPGA(implicit @unused bd: SOCTBdBuilder, @unused p: Parameters) extends IsXilinx with HasFriendlyName {

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

  /**
   * The default reset port for this FPGA board, based on the reset polarity parameter
   */
  lazy val defaultReset: FPGAResetPortType = {
    if (p(FPGAResetPolarity)) {
      FPGAResetPort("reset")
    } else {
      FPGAResetNPort("reset_n")
    }
  }

  override def toString: String = friendlyName

}