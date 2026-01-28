package soct.system.vivado.fpga

import org.chipsalliance.cde.config.Parameters
import soct.FPGAResetPolarity
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}
import soct.system.vivado.abstracts._

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
case class DDR4Port(override val instanceName: String)(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain] = None) extends XIntfPort {

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
    "CONFIG.POLARITY" -> "ACTIVE_HIGH"
  )
}

case class FPGAResetNPort(override val instanceName: String)(implicit bd: SOCTBdBuilder, p: Parameters) extends FPGAResetPortType with ResetN {
  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.POLARITY" -> "ACTIVE_LOW"
  )
}

/**
 * Case class representing a clock port provided by the FPGA board. This port can be used to drive clock domains within the design.
 *
 * @param instanceName The instance name of the clock port
 */
case class FPGAClockPort(override val instanceName: String)
                        (implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[FPGAClockDomain])
  extends XIntfPort with ProvidesAutoClock {

  require(dom.isDefined, s"FPGAClockPort $instanceName requires an associated FPGAClockDomain")

  override def mode: String = "Slave"

  override def partName: String = "xilinx.com:interface:diff_clock_rtl:1.0"

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.FREQ_HZ" -> (dom.get.freqMHz * 1e6).toInt.toString
  )

  override protected def outPortImpl(cd: ClockDomain, domIdx: Int, sinkPin: BdPinBase, pinIdx: Int): BdIntfPort = {
    BdIntfPort(instanceName, this)
  }

  override val domains: Seq[ClockDomain] = Seq(dom.get)
}

/**
 * Case class representing a clock domain provided by the FPGA board.
 *
 * @param freqMHz The frequency of the clock domain in MHz
 * @param reset   Optional reset provider that is synced to this clock domain
 */
final class FPGAClockDomain(override val freqMHz: Double, reset: ResetType)
                           (implicit bd: SOCTBdBuilder) extends ClockDomain(freqMHz, Some(reset)) {

  private var portOpt: Option[FPGAClockPort] = None

  /**
   * Get the associated FPGAClockPort for this clock domain.
   * @throws XilinxDesignException if no port is associated
   * @return The FPGAClockPort associated with this clock domain
   */
  @throws[XilinxDesignException]
  def port: FPGAClockPort = {
    portOpt.getOrElse(throw new XilinxDesignException(s"FPGAClockDomain $this has no associated FPGAClockPort"))
  }

  /**
   * Associate an FPGAClockPort with this clock domain.
   *
   * @param port The FPGAClockPort to associate
   * @return This FPGAClockDomain with the associated port
   */
  def withPort(port: FPGAClockPort): FPGAClockDomain = {
    portOpt = Some(port)
    this
  }
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
   * The clock domain representing the fastest clock available on this FPGA board.
   * Provides
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