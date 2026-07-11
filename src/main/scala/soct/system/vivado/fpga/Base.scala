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
 * A single FPGA pin of a DDR4 interface.
 *
 * @param signal     Logical DDR4 signal name matching the wrapper port suffix (e.g. "adr", "cs_n", "dq")
 * @param index      Bit index within the signal vector, None for scalar signals (act_n, reset_n)
 * @param loc        FPGA package pin (e.g. "AH16")
 * @param ioStandard Explicit IOSTANDARD, only where the board files declare one (e.g. reset_n:
 *                   LVCMOS12); all other DDR4 pins get their standards from the IP's own XDC
 * @param drive      Explicit DRIVE strength, only where the board files declare one
 */
case class DDR4Pin(signal: String, index: Option[Int], loc: String,
                   ioStandard: Option[String] = None, drive: Option[Int] = None)


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

  // ------------------------------------------------------------------------
  // CUSTOM (non board-flow) interface support: the board flow locks the DDR4
  // IP to the preset DIMM. Selecting a different module requires configuring
  // the IP without the board interface and providing the pin LOCs ourselves.
  // Boards declare the data below to enable this (validated live on Vivado
  // 2025.2: the IP accepts non-preset parts in Custom mode, derives timing
  // from the part, generates its own IOSTANDARD constraints, and only the
  // PACKAGE_PIN LOCs must come from a user XDC).
  // ------------------------------------------------------------------------

  /** DDR4 memory clock period in ps for custom mode (from the board preset, e.g. 938 = DDR4-2133) */
  def ddr4TimePeriodPs: Option[Int] = None

  /** DDR4 input (system) clock period in ps for custom mode (from the board preset, e.g. 3335) */
  def ddr4InputClockPeriodPs: Option[Int] = None

  /** DDR4 memory type for custom mode (matches the IP's C0.DDR4_MemoryType values) */
  def ddr4MemoryType: String = "SODIMMs"

  /** DDR4 data width for custom mode */
  def ddr4DataWidth: Int = 64

  /** Full FPGA pin map of the DDR4 interface (from the board files' part0_pins.xml), required for custom mode */
  def ddr4PinMap: Option[Seq[DDR4Pin]] = None

  /** True if this board port carries everything needed for a custom (non board-flow) DDR4 interface */
  def supportsCustomInterface: Boolean =
    ddr4PinMap.isDefined && ddr4TimePeriodPs.isDefined && ddr4InputClockPeriodPs.isDefined

  protected var customInterface: Boolean = false

  /** True if this port is configured as a custom (non board-flow) DDR4 interface */
  def isCustomInterface: Boolean = customInterface

  def withCustomInterface(): DDR4PortParams = {
    if (!supportsCustomInterface) {
      throw XilinxDesignException(s"DDR4 port $portName cannot use a custom interface: the board definition must declare ddr4PinMap, ddr4TimePeriodPs and ddr4InputClockPeriodPs.")
    }
    customInterface = true
    this
  }

  /**
   * Custom-interface ports are created by externalizing the configured controller pin instead of
   * create_bd_intf_port, so the port inherits the DIMM-dependent signal widths (a dual-rank
   * module widens cs_n/cke/odt/ck) - see [[soct.system.vivado.abstracts.ExternalizedIntfPort]].
   */
  override def initPort(implicit bd: SOCTBdBuilder, p: Parameters): BdIntfPortMaster = {
    if (isCustomInterface) {
      new BdIntfPortMaster with ExternalizedIntfPort {
        override def portName: String = DDR4PortParams.this.portName
        override def partName: String = DDR4PortParams.this.partName
      }
    } else {
      super.initPort
    }
  }
}


/**
 * Trait representing the parameters of a UART port on the FPGA board.
 */
trait UARTPortParams extends IsMasterIf {
  override def partName: String = "xilinx.com:interface:uart_rtl:1.0"
}


/**
 * Package pin location of a single-ended board signal (e.g. a reset pushbutton). Only needed
 * when the port has to be placed by an explicit XDC instead of a board interface association -
 * e.g. a DDR4 controller in custom (non board-flow) mode, where dropping RESET_BOARD_INTERFACE
 * also drops the board-file placement of the reset pin.
 */
case class BoardPin(loc: String, ioStandard: String)

/**
 * Case class representing a reset port on the FPGA board
 */
abstract class FPGAResetPortSource(implicit bd: SOCTBdBuilder, p: Parameters) extends BdVirtualPortI with ProvidesReset {
  override def ifType: String = "rst"

  /** Package pin of this reset input (e.g. ZCU104 CPU_RESET: M11/LVCMOS33), if the board defines it. */
  def pinLoc: Option[BoardPin] = None
}

case class FPGAResetPort(override val portName: String, override val pinLoc: Option[BoardPin] = None)
                        (implicit bd: SOCTBdBuilder, p: Parameters) extends FPGAResetPortSource with Reset {
  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.POLARITY" -> "ACTIVE_HIGH"
  )
}

case class FPGAResetNPort(override val portName: String, override val pinLoc: Option[BoardPin] = None)
                         (implicit bd: SOCTBdBuilder, p: Parameters) extends FPGAResetPortSource with ResetN {
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

/**
 * Package pin locations of a differential clock input pair.
 *
 * Only needed when the port has to be placed by an explicit XDC instead of a board interface
 * association - e.g. a DDR4 controller in custom (non board-flow) mode, where dropping
 * C0_CLOCK_BOARD_INTERFACE also drops the board-file placement of the system clock pins.
 *
 * @param clkP       Package pin of the positive leg (e.g. ZCU104: "AH18")
 * @param clkN       Package pin of the negative leg (e.g. ZCU104: "AH17")
 * @param ioStandard IOSTANDARD applied to both legs (e.g. "DIFF_SSTL12")
 */
case class DiffClockPins(clkP: String, clkN: String, ioStandard: String)

/** Differential (LVDS) clock input from the FPGA board (e.g. ZCU104 300 MHz diff pair) */
case class FPGADiffClockPort(override val portName: String, dom: () => ClockDomain,
                             pinLocs: Option[DiffClockPins] = None)
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