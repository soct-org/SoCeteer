package soct.system.vivado.components

import freechips.rocketchip.subsystem.ExtMem
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.abstracts._
import soct.system.vivado.fpga.{FPGADiffClockPort, FPGAResetPortSource, PartRegistry}
import soct.system.vivado.{DDR4Info, SOCTBdBuilder, StringToTCLCommand, TCLCommands, VivadoDesignException}

import scala.collection.mutable


/**
 * DDR4 memory controller component for Xilinx FPGAs.
 *
 * Two configuration modes:
 *  - BOARD FLOW (default): the DDR4 board interface is associated and Vivado configures the
 *    controller from the board preset - including the memory part, which is locked (a disabled
 *    parameter). Pin LOCs and IOSTANDARDs come from the board files.
 *  - CUSTOM (info.param.isCustomInterface): no board association; the memory part, type and
 *    clock timing are set explicitly from the board definition's DDR4PortParams, and the pin
 *    LOCs are emitted as an XDC from ddr4PinMap. The IP still generates its own IOSTANDARD /
 *    OUTPUT_IMPEDANCE constraints. This is the only way to run a DIMM that differs from the
 *    board preset (validated on Vivado 2025.2 with a dual-rank 16 GiB module on ZCU104).
 */
case class DDR4(info: DDR4Info)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip with ConnectOps with HasIndexedPins with HasBdAddr with EmitsConstraint {

  override def partName: String = "xilinx.com:ip:ddr4:2.2"

  object C0_DDR4 extends BdIntfPin("C0_DDR4", DDR4.this)

  info.ddr4Intf <-> C0_DDR4

  object C0_SYS_CLK extends BdIntfPin("C0_SYS_CLK", DDR4.this) with DrivenByNet

  object C0_SYS_CLK_I extends BdPinIn("c0_sys_clk_i", DDR4.this) // The unbuffered clock version

  object C0_DDR4_UI_CLK_SYNC_RST extends BdPinOut("c0_ddr4_ui_clk_sync_rst", DDR4.this)

  object C0_INIT_CALIB_COMPLETE extends BdPinOut("c0_init_calib_complete", DDR4.this)

  object C0_DDR4_UI_CLK extends BdPinOut("c0_ddr4_ui_clk", DDR4.this)

  object C0_DDR4_ARESETN extends BdPinIn("c0_ddr4_aresetn", DDR4.this)

  object SYS_RST extends BdPinIn("sys_rst", DDR4.this)

  object C0_DDR4_S_AXI extends BdIntfPin("C0_DDR4_S_AXI", DDR4.this) with DrivesNet

  case class ADDN_UI_CLKOUT_I(idx: Int, dom: ClockDomain) extends BdPinOut(s"addn_ui_clkout$idx", DDR4.this)

  object ADDN_UI_CLKOUT extends IndexedPinFactory[ADDN_UI_CLKOUT_I, ClockDomain](
    indexRange = (1, 4),
    pinConstructor = (idx, dom) => ADDN_UI_CLKOUT_I(idx, dom)
  )

  /**
   * @throws soct.system.vivado.VivadoDesignException if custom mode has no memory part set,
   *                                                  or both clock inputs are driven at once
   */
  override def defaultProperties: Map[String, String] = {
    val m = mutable.Map.empty[String, String]

    // NOTE: C0_DDR4_MEMORY_MAP_BASEADDR is deliberately NOT set here. The property parses its
    // value as DECIMAL (verified: "80000000" becomes 80e6, stored as a bitstring), so setting it
    // from a hex string was always wrong - the address segment assignment (assignAddrTcl) drives
    // the parameter via the segment's offset_base_param, which is the authoritative path.

    if (info.param.isCustomInterface) {
      // Custom (non board-flow) configuration: replicate the board preset's parameter set with
      // the requested part. The IP derives all part-dependent timing (CAS latency, rank count)
      // itself; pin LOCs are emitted via xdcCommands below.
      val part = info.param.getMemoryPart.getOrElse(
        throw VivadoDesignException(s"DDR4 $instanceName uses a custom interface but no memory part is set.")
      )
      m += "CONFIG.C0.DDR4_MemoryType" -> info.param.ddr4MemoryType
      m += "CONFIG.C0.DDR4_MemoryPart" -> PartRegistry.vivadoPartName(part)
      m += "CONFIG.C0.DDR4_DataWidth" -> info.param.ddr4DataWidth.toString
      m += "CONFIG.C0.DDR4_TimePeriod" -> info.param.ddr4TimePeriodPs.get.toString
      m += "CONFIG.C0.DDR4_InputClockPeriod" -> info.param.ddr4InputClockPeriodPs.get.toString
    } else {
      val boardRst = bd.singleConnector(SYS_RST, p => p.isInstanceOf[FPGAResetPortSource])
      m += "CONFIG.C0_DDR4_BOARD_INTERFACE" -> info.ddr4Intf.ref
      m += "CONFIG.RESET_BOARD_INTERFACE" -> boardRst.ref
    }

    val clkIn1Src = bd.sourceOf(C0_SYS_CLK_I)
    val clkIn1DSrc = bd.sourceOf(C0_SYS_CLK)

    if (clkIn1Src.isDefined && clkIn1DSrc.isDefined) {
      throw VivadoDesignException(s"DDR4 $instanceName C0_SYS_CLK and c0_sys_clk_i cannot both be connected to a source. Only one clock input can be used.")
    }

    (clkIn1DSrc, clkIn1Src) match {
      case (Some(c: FPGADiffClockPort), None) =>
        m += "CONFIG.System_Clock" -> "Differential"
        if (!info.param.isCustomInterface) {
          // Board association of the input clock only in board-flow mode; in custom mode the
          // clock port keeps its own board association for pin placement, and the controller
          // uses the explicit InputClockPeriod above.
          m += "CONFIG.C0_CLOCK_BOARD_INTERFACE" -> c.ref
        }
      case _ =>
        soct.log.warn(s"DDR4 $instanceName C0_SYS_CLK is not connected to a differential clock source. Using unbuffered clock input c0_sys_clk_i instead.")
        m += "CONFIG.System_Clock" -> "No_Buffer"
    }


    ADDN_UI_CLKOUT.all.foreach {
      case (idx, clk) =>
        // Xilinx quirk: the parameter is named ..._FREQ_HZ but the IP expects the value in MHz
        // (the catalog lists values like "50", "100").
        m += s"CONFIG.ADDN_UI_CLKOUT${idx}_FREQ_HZ" -> clk.dom.freq.toMHz.toInt.toString
    }

    m.toMap
  }

  /** Pin LOC constraints for the custom (non board-flow) interface; empty in board-flow mode
   * (there the board files place the pins). IOSTANDARD etc. always come from the IP's own XDC. */
  override def xdcName()(implicit bd: SOCTBdBuilder): String = s"${instanceName}_pins"

  /**
   * @throws soct.system.vivado.VivadoDesignException if custom mode is active but the board
   *                                                  definition lacks the DDR4 pin map, the
   *                                                  clock pin locations, or the reset pin location
   */
  override def xdcCommands()(implicit bd: SOCTBdBuilder): TCLCommands = {
    if (!info.param.isCustomInterface) return Seq.empty
    val pins = info.param.ddr4PinMap.getOrElse(
      throw VivadoDesignException(s"DDR4 $instanceName uses a custom interface but the board definition provides no ddr4PinMap.")
    )
    val portPrefix = info.ddr4Intf.portName
    val ddr4Cmds = pins.flatMap { pin =>
      val port = pin.index match {
        case Some(i) => s"${portPrefix}_${pin.signal}[$i]"
        case None => s"${portPrefix}_${pin.signal}"
      }
      Seq(s"set_property PACKAGE_PIN ${pin.loc} [get_ports {$port}]".tcl) ++
        pin.ioStandard.map(ios => s"set_property IOSTANDARD $ios [get_ports {$port}]".tcl) ++
        pin.drive.map(d => s"set_property DRIVE $d [get_ports {$port}]".tcl)
    }

    // In board flow, C0_CLOCK_BOARD_INTERFACE is what places the system clock pins from the
    // board files; without it the MIG aborts opt_design with Mig 66-99 ("c0_sys_clk_p/n not
    // placed"). The custom interface therefore has to place the clock pair itself.
    val clkCmds = bd.sourceOf(C0_SYS_CLK) match {
      case Some(c: FPGADiffClockPort) =>
        val locs = c.pinLocs.getOrElse(
          throw VivadoDesignException(s"DDR4 $instanceName uses a custom interface, but the differential system clock port ${c.portName} has no pin locations (FPGADiffClockPort.pinLocs). Add them to the board definition.")
        )
        Seq(s"${c.portName}_clk_p" -> locs.clkP, s"${c.portName}_clk_n" -> locs.clkN).flatMap {
          case (port, loc) => Seq(
            s"set_property PACKAGE_PIN $loc [get_ports {$port}]".tcl,
            s"set_property IOSTANDARD ${locs.ioStandard} [get_ports {$port}]".tcl
          )
        }
      case _ => Seq.empty // unbuffered c0_sys_clk_i: driven inside the BD, nothing to place
    }

    // Same duty transfer for the board reset: in board flow, RESET_BOARD_INTERFACE places it
    // from the board files; without that param the port survives to write_bitstream and then
    // fails DRC NSTD-1/UCIO-1.
    val rstCmds = {
      val boardRst = bd.singleConnector(SYS_RST, p => p.isInstanceOf[FPGAResetPortSource])
        .asInstanceOf[FPGAResetPortSource]
      val pin = boardRst.pinLoc.getOrElse(
        throw VivadoDesignException(s"DDR4 $instanceName uses a custom interface, but the board reset port ${boardRst.portName} has no pin location (FPGAResetPortSource.pinLoc). Add it to the board definition.")
      )
      Seq(
        s"set_property PACKAGE_PIN ${pin.loc} [get_ports {${boardRst.portName}}]".tcl,
        s"set_property IOSTANDARD ${pin.ioStandard} [get_ports {${boardRst.portName}}]".tcl
      )
    }
    ddr4Cmds ++ clkCmds ++ rstCmds
  }

  /**
   * @throws soct.system.vivado.VivadoDesignException if the address aperture is not positive
   */
  override def assignAddrTcl: TCLCommands = {
    // assign_bd_address requires a power-of-two range, but the aperture
    // (memory base + capacity) is not one whenever capacity != base, e.g.
    // 2 GiB base + 16 GiB DIMM = 18 GiB. Round up to the next power of two;
    // the DDR4 controller only responds within [BASEADDR, BASEADDR + capacity),
    // so the oversized head/tail of the segment is never addressed.
    val aperture = BigInt(info.param.getCap.value) + p(ExtMem).get.master.base
    if (aperture <= 0) {
      throw VivadoDesignException(s"DDR4 $instanceName has a non-positive address aperture ($aperture). Check the memory capacity and ExtMem base address.")
    }
    val range = BigInt(1) << (aperture - 1).bitLength
    if (range != aperture) {
      soct.log.debug(s"DDR4 $instanceName aperture 0x${aperture.toString(16)} is not a power of two; assigning address range 0x${range.toString(16)} instead.")
    }
    Seq(
      s"assign_bd_address -offset 0 -range 0x${range.toString(16).toUpperCase} -target_address_space [get_bd_addr_spaces ${info.axiAddrSpacePin.ref}] [get_bd_addr_segs $bdPath/C0_DDR4_MEMORY_MAP/C0_DDR4_ADDRESS_BLOCK]".tcl
    )
  }
}