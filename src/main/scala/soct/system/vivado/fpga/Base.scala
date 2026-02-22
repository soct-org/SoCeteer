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
case class DDR4Port(override val portName: String)(implicit bd: SOCTBdBuilder, p: Parameters) extends BdIntfPortMaster {
  override def partName: String = "xilinx.com:interface:ddr4_rtl:1.0"
}


/**
 * Case class representing a UART port on the FPGA board.
 */
case class UARTPort(override val portName: String)(implicit bd: SOCTBdBuilder, p: Parameters) extends BdIntfPortMaster {
  override def partName: String = "xilinx.com:interface:uart_rtl:1.0"
}


/**
 * Case class representing a reset port on the FPGA board
 */
abstract class FPGAResetPortSource(implicit bd: SOCTBdBuilder, p: Parameters) extends BdVirtualPortI with ProvidesReset {
  override def ifType: String = "rst"
}

case class FPGAResetPort(override val portName: String)(implicit bd: SOCTBdBuilder, p: Parameters) extends FPGAResetPortSource with Reset {
  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.POLARITY" -> "ACTIVE_HIGH"
  )
}

case class FPGAResetNPort(override val portName: String)(implicit bd: SOCTBdBuilder, p: Parameters) extends FPGAResetPortSource with ResetN {
  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.POLARITY" -> "ACTIVE_LOW"
  )
}

/**
 * Case class representing a clock port provided by the FPGA board. This port can be used to drive clock domains within the design.
 *
 * @param portName The name of the clock port, which should match the name of the corresponding clock pin on the FPGA board.
 * @param dom A function that returns the clock domain associated with this clock port. This allows for lazy evaluation of the clock domain, which can be useful for handling circular dependencies between the clock port and the clock domain.
 */
case class FPGAClockPort(override val portName: String, dom: () => ClockDomain)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdIntfPortSlave with DrivesNet {

  override def partName: String = "xilinx.com:interface:diff_clock_rtl:1.0"

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.FREQ_HZ" -> (dom().freqMHz * 1e6).toInt.toString
  )
}

/**
 * Case class representing a clock domain provided by the FPGA board.
 *
 * @param clock   Clock provider that is synced to this clock domain
 * @param reset   Reset provider that is synced to this clock domain
 * @param freqMHz The frequency of the clock domain in MHz
 */
case class FPGAClockDomain(clock: FPGAClockPort, reset: FPGAResetPortSource, override val freqMHz: Double)
                          (implicit bd: SOCTBdBuilder) extends ClockDomain(freqMHz)

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
   */
  val fastestClock: FPGAClockDomain

  /**
   * Get the i-th DDR4 port available on this FPGA board.
   *
   * @param i The index of the DDR4 port to initialize (default is 0)
   * @throws XilinxDesignException if no DDR4 ports are defined for this FPGA board or if the index is out of range
   * @return The initialized DDR4 port
   */
  @throws[XilinxDesignException]
  def initDDR4Port(i: Int = 0): DDR4Port = throw XilinxDesignException(s"FPGA ${friendlyName} does not have any DDR4 ports defined.")


  /**
   * Get the i-th UART port available on this FPGA board.
   *
   * @param i The index of the UART port to initialize (default is 0)
   * @throws XilinxDesignException if no UART ports are defined for this FPGA board or if the index is out of range
   * @return The initialized UART port
   */
  @throws[XilinxDesignException]
  def initUARTPort(i: Int = 0): UARTPort = throw XilinxDesignException(s"FPGA ${friendlyName} does not have any UART ports defined.")


  /**
   * The PMOD ports available on this FPGA board
   */
  val getPMODPorts: Seq[Int] = Seq.empty


  /**
   * The default reset port for this FPGA board, based on the reset polarity parameter
   */
  lazy val defaultReset: FPGAResetPortSource = {
    if (p(FPGAResetPolarity)) {
      FPGAResetPort("reset")
    } else {
      FPGAResetNPort("reset_n")
    }
  }

  override def toString: String = friendlyName
}