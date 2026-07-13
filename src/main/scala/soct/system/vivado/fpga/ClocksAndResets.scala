package soct.system.vivado.fpga

import org.chipsalliance.cde.config.Parameters
import soct.SOCTFreq.Freq
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts._


/**
 * Package pin location of a single-ended board signal (e.g. a reset pushbutton). Only needed
 * when the port has to be placed by an explicit XDC instead of a board interface association -
 * e.g. a DDR4 controller in custom (non board-flow) mode, where dropping RESET_BOARD_INTERFACE
 * also drops the board-file placement of the reset pin.
 *
 * @param loc        FPGA package pin (e.g. "M11")
 * @param ioStandard IOSTANDARD of the pin (e.g. "LVCMOS33")
 */
case class BoardPin(loc: String, ioStandard: String)

/**
 * A reset input port provided by the FPGA board.
 */
abstract class FPGAResetPortSource(implicit bd: SOCTBdBuilder, p: Parameters) extends BdVirtualPortI with ProvidesReset {
  override def ifType: String = "rst"

  /** Package pin of this reset input (e.g. ZCU104 CPU_RESET: M11/LVCMOS33), if the board defines it. */
  def pinLoc: Option[BoardPin] = None
}

/** Active-high reset input from the FPGA board (e.g. a pushbutton). */
case class FPGAResetPort(override val portName: String, override val pinLoc: Option[BoardPin] = None)
                        (implicit bd: SOCTBdBuilder, p: Parameters) extends FPGAResetPortSource with Reset {
  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.POLARITY" -> "ACTIVE_HIGH"
  )
}

/** Active-low reset input from the FPGA board. */
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
    "CONFIG.FREQ_HZ" -> dom().freq.toHz.toLong.toString
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
 * @param clock Clock provider synced to this clock domain
 * @param reset Reset provider synced to this clock domain
 * @param freq  The frequency of the clock domain
 */
case class FPGAClockDomain(clock: FPGAClockPort, reset: FPGAResetPortSource, override val freq: Freq)
                          (implicit bd: SOCTBdBuilder) extends ClockDomain(freq)
