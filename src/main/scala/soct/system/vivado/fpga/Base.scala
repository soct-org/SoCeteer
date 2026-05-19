package soct.system.vivado.fpga

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands, XilinxDesignException}
import soct.system.vivado.abstracts._
import soct.system.vivado.misc.{FPGAPMODPin, RawPMODPin}

import scala.annotation.unused


/**
 * Registry for known FPGA boards.
 */
object FPGARegistry {

  // TODO ADD YOUR BOARD HERE! - Use uppercase names as keys
  private val registry: Map[String, FPGA] = Map(
    "ZCU104" -> ZCU104
  )

  def getKnownBoards: Seq[String] = registry.keys.toSeq

  /** name -> Board (throws if not found) */
  def n2b(name: String): FPGA = {
    registry.getOrElse(name.toUpperCase, throw new Exception(s"Unknown FPGA board: $name"))
  }

  /** name -> Board */
  def n2bOpt(name: String): Option[FPGA] = {
    registry.get(name.toUpperCase)
  }

  /** Board -> name (throws if not found) */
  def b2n(fpga: FPGA): String = {
    registry.find(_._2 == fpga) match {
      case Some((name, _)) => name
      case None => throw new Exception(s"FPGA '${fpga.friendlyName}' not found in registry")
    }
  }

  /** Board -> name */
  def b2nOpt(fpga: FPGA): Option[String] = {
    registry.find(_._2 == fpga).map(_._1)
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
 * Abstract base class for clock input ports provided by the FPGA board.
 * Concrete subclasses differ only in the Xilinx interface type (single-ended vs differential).
 */
abstract class FPGAClockPort(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdIntfPortSlave with DrivesNet {
  def dom: () => ClockDomain

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.FREQ_HZ" -> (dom().freqMHz * 1e6).toInt.toString
  )
}

/** Single-ended clock input from the FPGA board (e.g. a 100 MHz XTAL oscillator) */
case class FPGASingleEndedClockPort(override val portName: String, dom: () => ClockDomain)
                                   (implicit bd: SOCTBdBuilder, p: Parameters)
  extends FPGAClockPort {
  override def partName: String = "xilinx.com:interface:clk_rtl:1.0"
}

/** Differential (LVDS) clock input from the FPGA board (e.g. ZCU104 300 MHz diff pair) */
case class FPGADiffClockPort(override val portName: String, dom: () => ClockDomain)
                            (implicit bd: SOCTBdBuilder, p: Parameters)
  extends FPGAClockPort {
  override def partName: String = "xilinx.com:interface:diff_clock_rtl:1.0"
}

/**
 * Case class representing a clock domain provided by the FPGA board.
 *
 * @param clock   Clock provider synced to this clock domain
 * @param reset   Reset provider synced to this clock domain
 * @param freqMHz The frequency of the clock domain in MHz
 */
case class FPGAClockDomain(clock: FPGAClockPort, reset: FPGAResetPortSource, override val freqMHz: Double)
                          (implicit bd: SOCTBdBuilder) extends ClockDomain(freqMHz)

/**
 * Abstract base class for FPGA boards. Subclasses must provide information about the specific FPGA board,
 * such as the Xilinx part number, available clock domains, DDR4 ports, and PMOD ports.
 * For instance, see the ZCU104 implementation.
 * All init functions have side-effects, and should be called only once per block design.
 */
abstract class FPGA extends IsXilinx with HasFriendlyName {

  /**
   * The Xilinx part number for this FPGA board - e.g., "xczu7ev-ffvc1156-2-e"
   */
  val xilinxPart: String

  /**
   * The size of the DDR memory on this FPGA board in bytes, if it is a fixed known size
   * (e.g., a board with soldered-on DDR4 of a specific capacity).
   * Returns None if the board has no fixed DDR size
   */
  @unused
  val intMemCap: Option[BigInt] = None


  /**
   * The PMOD ports available on this FPGA board
   */
  val getPMODPorts: Seq[Int] = Seq.empty

  /**
   * Get the PMOD pin corresponding to the given PMOD port and pin index.
   *
   * @param pmodPort The PMOD port number (e.g., 0, 1, 2) to which the component is connected in the block design
   * @param pmodPin  The index of the PMOD pin within the specified PMOD port (e.g., 0-7 for an 8-pin PMOD)
   * @throws XilinxDesignException if the specified PMOD port or pin index is not defined for this FPGA board
   * @return The PmodPin object representing the specified PMOD pin
   */
  def pmod(pmodPort: Int, pmodPin: RawPMODPin): FPGAPMODPin

  /**
   * Get the i-th DDR4 port available on this FPGA board.
   *
   * @param i The index of the DDR4 port to initialize (default is 0)
   * @throws XilinxDesignException if no DDR4 ports are defined for this FPGA board or if the index is out of range
   * @return The initialized DDR4 port
   */
  @throws[XilinxDesignException]
  def initDDR4Port(i: Int = 0)(implicit bd: SOCTBdBuilder, p: Parameters): DDR4Port = throw XilinxDesignException(s"FPGA ${friendlyName} does not have any DDR4 ports defined.")


  /**
   * Get the i-th UART port available on this FPGA board.
   *
   * @param i The index of the UART port to initialize (default is 0)
   * @throws XilinxDesignException if no UART ports are defined for this FPGA board or if the index is out of range
   * @return The initialized UART port
   */
  @throws[XilinxDesignException]
  def initUARTPort(i: Int = 0)(implicit bd: SOCTBdBuilder, p: Parameters): UARTPort = throw XilinxDesignException(s"FPGA ${friendlyName} does not have any UART ports defined.")


  /**
   * The clock domain representing the fastest clock available on this FPGA board.
   */
  def initFastestClock(implicit bd: SOCTBdBuilder, p: Parameters): FPGAClockDomain


  override def toString: String = friendlyName
}