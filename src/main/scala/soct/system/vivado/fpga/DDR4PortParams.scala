package soct.system.vivado.fpga

import org.chipsalliance.cde.config.Parameters
import soct.SOCTBytes._
import soct.system.vivado.abstracts.{BdIntfPort, PortCreation, PortMode}
import soct.system.vivado.{SOCTBdBuilder, VivadoDesignException}


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
 * Parameters of one DDR4 memory port (DIMM socket or on-board memory) of an FPGA board.
 *
 * Board definitions declare the static board data (preset part, custom-interface pin map and
 * clock timing); the launcher then resolves the selected memory part and capacity onto the
 * port via [[withMemoryPart]]/[[withCap]], and switches it to the custom (non board-flow)
 * interface via [[withCustomInterface]] when the selected DIMM differs from the board preset.
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


  /**
   * The capacity of this DDR4 port.
   *
   * @return the usable capacity in bytes
   * @throws soct.system.vivado.VivadoDesignException if no capacity has been set (see [[withCap]])
   */
  def getCap: Bytes = {
    capOpt.getOrElse(throw VivadoDesignException(s"DDR4 port ${portName} capacity is not defined"))
  }


  /**
   * Set the capacity of this DDR4 port.
   *
   * @param cap the usable capacity in bytes
   * @return this port, for chaining
   */
  def withCap(cap: Bytes): DDR4PortParams = {
    capOpt = Some(cap)
    this
  }


  /**
   * The Vivado memory part selected for this port, if one has been set.
   *
   * @return the part name, or None
   */
  def getMemoryPart: Option[String] = memoryPartOpt


  /**
   * Select the Vivado memory part for this port.
   *
   * @param part the full part name (e.g. "MTA16ATF2G64HZ-2G3")
   * @return this port, for chaining
   */
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

  /**
   * Switch this port to the custom (non board-flow) DDR4 interface.
   *
   * @return this port, for chaining
   * @throws soct.system.vivado.VivadoDesignException if the board definition lacks the
   *                                                  custom-interface data (see [[supportsCustomInterface]])
   */
  def withCustomInterface(): DDR4PortParams = {
    if (!supportsCustomInterface) {
      throw VivadoDesignException(s"DDR4 port $portName cannot use a custom interface: the board definition must declare ddr4PinMap, ddr4TimePeriodPs and ddr4InputClockPeriodPs.")
    }
    customInterface = true
    this
  }

  /**
   * Custom-interface ports are created by externalizing the configured controller pin instead of
   * create_bd_intf_port, so the port inherits the DIMM-dependent signal widths (a dual-rank
   * module widens cs_n/cke/odt/ck) - see [[soct.system.vivado.abstracts.PortCreation.Externalize]].
   */
  override def initPort(implicit bd: SOCTBdBuilder, p: Parameters): BdIntfPort = {
    val creation = if (isCustomInterface) PortCreation.Externalize else PortCreation.Declare
    BdIntfPort(portName, partName, PortMode.Master, creation)
  }
}
