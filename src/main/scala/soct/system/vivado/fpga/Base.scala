package soct.system.vivado.fpga

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands, XilinxDesignException}
import soct.system.vivado.abstracts._
import soct.system.vivado.misc.{FPGAPMODPin, RawPMODPin}
import soct.SOCTBytes._

trait IsMasterIf {
  /**
   * The name of the port in the block design
   */
  val portName: String

  /**
   * The Xilinx interface type for this port in the block design.
   */
  def partName: String


  /**
   * Instantiates a BdIntfPortMaster from this CanBePort, using the implicit SOCTBdBuilder and Parameters.
   * @return the BdIntfPortMaster instance
   */
  def initPort(implicit bd: SOCTBdBuilder, p: Parameters): BdIntfPortMaster = {
    new BdIntfPortMaster {
      override def portName: String = IsMasterIf.this.portName
      override def partName: String = IsMasterIf.this.partName
    }
  }
}


/**
 * Trait representing the parameters of a DDR4 port on the FPGA board.
 */
trait DDR4PortParams extends IsMasterIf {

  override def partName: String = "xilinx.com:interface:ddr4_rtl:1.0"

  /**
   * The DIMM enforced by the board's DDR4 board-interface preset. Vivado's board flow locks the
   * controller's C0.DDR4_MemoryPart to this preset (the parameter is disabled and set_property
   * on it is ignored), so it defines what the generated design can actually address - regardless
   * of the module physically inserted in the slot. Board definitions using board-flow DDR4
   * interfaces should declare it; the port's default capacity is derived from it.
   * None if unknown (e.g. future custom, non-board-flow interfaces).
   */
  def defaultMemoryPart: Option[String] = None

  /**
   * Optional capacity of the DDR4 memory port in bytes - only known for on-board memory like on the VCU108
   */
  protected var capOpt: Option[Bytes] = None

  /**
   * Optional Vivado memory part name for this port (e.g. "MTA8ATF1G64HZ-2G6E1"). Needed whenever the
   * inserted DIMM differs from the board preset: the DDR4 IP sizes its address decode window from the
   * selected part, so a mismatch silently truncates the usable memory to the preset's capacity.
   * The part must be available in the Vivado DDR4 IP part catalog for the target board.
   */
  protected var memoryPartOpt: Option[String] = None


  def getCap: Bytes = {
    capOpt.getOrElse(throw new Exception(s"DDR4 port ${portName} capacity is not defined"))
  }


  def withCap(cap: Bytes): DDR4PortParams = {
    capOpt = Some(cap)
    this
  }


  def getMemoryPart: Option[String] = memoryPartOpt


  def withMemoryPart(part: String): DDR4PortParams = {
    memoryPartOpt = Some(part)
    this
  }
}


/**
 * Trait representing the parameters of a UART port on the FPGA board.
 */
trait UARTPortParams extends IsMasterIf {
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

  def intDDR4Ports: Seq[DDR4PortParams] = Seq.empty

  def extDDR4Ports: Seq[DDR4PortParams] = Seq.empty

  def uartPorts: Seq[UARTPortParams] = Seq.empty

  def initNClockPorts(n: Int)(implicit bd: SOCTBdBuilder, p: Parameters): Seq[FPGAClockDomain]

  override def toString: String = friendlyName
}