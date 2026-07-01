package soct.system.vivado

import chisel3._
import freechips.rocketchip.resources.ResourceInt
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.InModuleBody
import soct.SOCTBytes.Bytes
import soct._
import soct.system.soceteer.SOCTSystem
import soct.system.vivado.abstracts.BdPinPort.portToBdPin
import soct.system.vivado.abstracts._
import soct.system.vivado.components._
import soct.system.vivado.fpga.{DDR4PortParams, FPGAClockDomain, UARTPortParams}
import soct.system.vivado.intf.JTAGIntf
import soct.system.vivado.misc.{AXI4BusInfo, AxiSlaveBinder, DTSInfo, Irq}

/**
 * Top-level module for synthesis of the RocketSystem within SOCT using Vivado
 */
class SOCTVivadoSystemMultiMem(implicit p: Parameters) extends SOCTSystem {
  throw new NotImplementedError("SOCTVivadoSystemMultiMem is not yet implemented. Please use SOCTVivadoSystem for now.")
  implicit val bd: SOCTBdBuilder = p(BdBuilderKey).getOrElse(
    throw new XilinxDesignException("SOCTVivadoSystem requires a BdBuilder to be set in parameters for block design generation.")
  )

  /**
   * Bind a clock-output pin (by hierarchical path) to a triple of TCL variables:
   *   - `<varBase>`: the pin handle
   *   - `<varBase>_clk`: the `get_clocks` object driving it
   *   - `<varBase>_period`: its min PERIOD
   *
   * Pure TCL plumbing — no topology-specific assumptions baked in. Used by the
   * timing-constraint block below to turn pin paths into reusable handles.
   *
   * @param pinPath hierarchical pin path (typically `<instanceName>/<pin>`),
   *                e.g. `s"${ddr4.instanceName}/addn_ui_clkout2"`. Matched with
   *                a leading `*` and `-hier`, so partial paths work.
   * @param varBase base TCL variable name (e.g. `"core_clock"`)
   * @return (TCL commands, clockVarName, periodVarName)
   */
  private def captureClock(pinPath: String, varBase: String): (TCLCommands, String, String) = {
    val clkVar = s"${varBase}_clk"
    val perVar = s"${varBase}_period"
    val cmd =
      s"""# Capture clock object from $pinPath
         |set $varBase [get_pins -quiet -hier *$pinPath]
         |set $clkVar [get_clocks -of_objects $$$varBase]
         |set $perVar [get_property -min PERIOD $$$clkVar]
         |""".stripMargin.tcl
    (Seq(cmd), clkVar, perVar)
  }

  //-------------------------------------------------------------------------
  // Device tree generation
  // (must be done before module instantiation since some components bind resources during construction)
  //-------------------------------------------------------------------------
  val plicDev = plicOpt.getOrElse(
    throw new XilinxDesignException("SOCTVivadoSystem requires a PLIC to be present in the system for interrupt wiring.")
  ).device

  var irqIdx = 0

  val uartDTSOpt = if (p(HasUART)) {
    val dts = DTSInfo(
      parent = mmioBusDevice.get,
      regs = Seq(("reg", 0x60010000L, 0x10000L)),
      irqs = Seq(Irq(plicDev, irqIdx)),
      compatibles = Seq("riscv,axi-uart-1.0"),
      extraProps = Map("port-number" -> Seq(ResourceInt(0)))
    )
    irqIdx += 1
    Some(dts)
  } else None

  uartDTSOpt.foreach { dts =>
    AxiSlaveBinder.bindSimpleDevice(
      devname = "uart0",
      dts = dts,
      perms = AxiSlaveBinder.mmioPerms
    )
  }

  val sdDTSOpt = p(HasSDCardPMOD).map { idx =>
    val sdDTS = DTSInfo(
      parent = mmioBusDevice.get,
      regs = Seq(("reg", 0x60000000L, 0x10000L)),
      irqs = Seq(Irq(plicDev, irqIdx)),
      compatibles = Seq("riscv,axi-sd-card-1.0"),
      extraProps = Map(
        "clock" -> Seq(ResourceInt(100000000)),
        "bus-width" -> Seq(ResourceInt(4)),
        "fifo-depth" -> Seq(ResourceInt(256)),
        "max-frequency" -> Seq(ResourceInt(300000000)),
        "cap-sd-highspeed" -> Nil,
        "cap-mmc-highspeed" -> Nil,
        "no-sdio" -> Nil
      )
    )
    irqIdx += 1
    AxiSlaveBinder.bindSimpleDevice(
      devname = "mmc0",
      dts = sdDTS,
      perms = AxiSlaveBinder.mmioPerms
    )
    sdDTS
  }

  InModuleBody {

    // --------------------------------------------------------------------------
    // Board / Top init
    // --------------------------------------------------------------------------
    val fpga = p(XilinxFPGAKey).getOrElse(throw new XilinxDesignException("XilinxFPGAKey not set in parameters."))
    val FPGAClockDomain(fpgaClk, fpgaRst, boardClkSpeed) = fpga.initNClockPorts(1).head

    val top = new SOCTVivadoSystemTop(this)
    bd.init(p, top, fpga)

    val Seq(axiMems, _axiMMIOs, _axiL2Frontends) = top.axi4BusMapping
    require(_axiMMIOs.size == 1, s"Expected exactly one AXI4 MMIO interface but found ${_axiMMIOs.size}")
    require(_axiL2Frontends.size == 1, s"Expected exactly one AXI4 DMA interface but found ${_axiL2Frontends.size}")
    val axiMMIO = _axiMMIOs.head.bdPin
    val axiDMA = _axiL2Frontends.head.bdPin

    // The Clock and Reset pins from the top
    val clocks = top.ioClocksMapping.values.toSeq
    val clockPins = top.ioClocksMapping.map(_._2.clkPin).toSeq
    val resetPins = top.ioClocksMapping.map(_._2.assocRstPin).toSeq

    // --------------------------------------------------------------------------
    // Clock domains
    // --------------------------------------------------------------------------
    val peripheryDomain = new ClockDomain(
      freqMHz = p(PeripheryClockDomain),
    )

    // TODO Currently, this design only supports a single clock domain for the buses, but we should enable multiple clock domains for different buses in the future.
    val freqs = clocks.flatMap(_.freqHz).distinct
    if (freqs.size != 1) {
      throw new XilinxDesignException(s"Multiple frequencies ${freqs.mkString(", ")} found for clock bundles ${clocks.map(_.clkPin).mkString(", ")}. This is not currently supported in SOCTVivadoSystem, which only supports a single clock domain for the buses.")
    }
    val coreDomain = new ClockDomain(
      freqMHz = freqs.head.toDouble / 1e6, // Convert from Hz to MHz
    )

    val memDomain = new ClockDomain(
      freqMHz = boardClkSpeed
    )

    // --------------------------------------------------------------------------
    // Board ports
    // --------------------------------------------------------------------------
    if (axiMems.length != p(RegisteredMems).length)
      throw XilinxDesignException("The number of AXI4 memory interfaces of the RocketSystem does not match the number of DDR4 memory layouts specified in RegisteredMems.")

    val ddr4Params: Seq[DDR4Info] = p(RegisteredMems).zipWithIndex.map{
      case (param, i) => DDR4Info(param.getCap, param, param.initPort, axiMems(i))
    }

    val uartParamOpt: Option[UARTPortParams] = {
      if (p(HasUART)) {
        if (fpga.uartPorts.isEmpty) {
          throw new XilinxDesignException(s"FPGA ${fpga.friendlyName} does not have any UART ports defined, but HasUART is set to true in parameters.")
        }
        Some(fpga.uartPorts.head)
      } else None
    }

    // --------------------------------------------------------------------------
    // Components
    // --------------------------------------------------------------------------

    val oz = ClkWiz().withInstanceName("clk_wiz")
    val periphPsr = ProcSysReset().withInstanceName("periph_psr")
    val corePsr = ProcSysReset().withInstanceName("core_psr")

    val uartOpt = uartParamOpt.map { uartParams =>
      val port = uartParams.initPort
      AXIUartLite(uartDTSOpt.get, axiMMIO, port, uartParams)
    }

    val memPaths = ddr4Params.zipWithIndex.map { case (info, i) =>
      MemPath(info, DDR4(info.mAxi.bdPin, info.port, info.param), AXISmartConnect().withInstanceName(s"mem_smc_$i"))
    }

    val mmioSMC = AXISmartConnect().withInstanceName("mmio_smc")
    val dmaSMC = AXISmartConnect().withInstanceName("dma_smc")

    val interruptConcat = InlineConcat(nExtInterrupts)

    // --------------------------------------------------------------------------
    // Derived pins (clock outputs / DDR helper pins)
    // --------------------------------------------------------------------------

    val coreClock = oz.CLK_OUT(1, coreDomain)
    val peripheryClock = oz.CLK_OUT(2, peripheryDomain)
    val memClock = oz.CLK_OUT(3, memDomain)

    // --------------------------------------------------------------------------
    // Timing constraints
    // --------------------------------------------------------------------------
    val (coreClockTCL, coreClockObj, corePeriodProp) =
      captureClock(coreClock.ref, "core_clock")

    bd.addTimingConstraints(() => coreClockTCL)

    memPaths.map(_.ddr4Inst).foreach {
      ddr4 =>
        bd.addTimingConstraints(() => Seq(
          s"""# Timing constraints for DDR4 controller (${ddr4.instanceName})
             |set ddrmc_inst [get_cells -hier ${ddr4.instanceName}]
             |set_false_path -through [get_pins $$ddrmc_inst/${ddr4.SYS_RST.pin}]
             |set_false_path -through [get_pins $$ddrmc_inst/${ddr4.C0_INIT_CALIB_COMPLETE.pin}]
             |set ddrc_clock [get_clocks -of_objects [get_pins $$ddrmc_inst/${ddr4.C0_DDR4_UI_CLK.pin}]]
             |set ddrc_clock_period [get_property -min PERIOD $$ddrc_clock]
             |set_max_delay -from $$$coreClockObj -to $$ddrc_clock -datapath_only $$ddrc_clock_period
             |set_max_delay -from $$ddrc_clock -to $$$coreClockObj -datapath_only $$$corePeriodProp
             |""".stripMargin.tcl
        ))
    }

    // --------------------------------------------------------------------------
    // Fundamental interconnect (interfaces & major clocks)
    // --------------------------------------------------------------------------
    memPaths.map(_.ddr4Inst) foreach { ddr4 =>
      memClock --> ddr4.C0_SYS_CLK_I
      fpgaRst --> ddr4.SYS_RST
    }
    fpgaRst --> oz.RESET
    fpgaClk --> oz.CLK_IN_D(1)

    // --------------------------------------------------------------------------
    // Reset strategy
    // --------------------------------------------------------------------------
    // ndreset from the debug module resets core and periphery but not DDR or JTAG:
    // DDR must not be re-initialized on debug reset; JTAG is separately tied off below.
    if (debug.isDefined && !p(soct.FastPnR)) {
      OR(fpgaRst, portToBdPin(debug.getWrappedValue.get.ndreset))
        .withInstanceName("ndreset_or_sys_rst") --> Seq(periphPsr.EXT_RESET_IN, corePsr.EXT_RESET_IN)
    } else {
      soct.log.info("[FastPnR] The core cannot be reset using a debugger.")
      fpgaRst --> Seq(periphPsr.EXT_RESET_IN, corePsr.EXT_RESET_IN)
    }


    val calibSignals = memPaths.map(_.ddr4Inst.C0_INIT_CALIB_COMPLETE.asInstanceOf[BdPinOut])
    val ddr4InitComplete: BdPinOut = calibSignals.zipWithIndex.tail.foldLeft(calibSignals.head) {
      case (acc, (next, j)) =>
        AND(acc, next).withInstanceName(s"ddr4_calib_complete_gate_$j").RES
    }

    oz.LOCKED --> Seq(periphPsr.DCM_LOCKED, corePsr.DCM_LOCKED)

    // Domain clocks:
    // Periphery domain clock drives periph reset sync + periph-ish IP clocks
    peripheryClock --> Seq(
      periphPsr.SLOWEST_SYNC_CLK,
      mmioSMC.ACLK(0),
      dmaSMC.ACLK(0)
    )
    uartOpt.foreach(uart => peripheryClock --> uart.S_AXI_ACLK)

    // Core domain clock drives core reset sync + top clocks + one mem SMC clock
    coreClock --> Seq(
      corePsr.SLOWEST_SYNC_CLK,
      mmioSMC.ACLK(1),
      dmaSMC.ACLK(1),
    )
    coreClock --> clockPins
    coreClock --> memPaths.map(_.memSMC.ACLK(0)) // Core clock influences all memSMCs

    memPaths.foreach { path =>
      path.ddr4Inst.C0_DDR4_UI_CLK --> path.memSMC.ACLK(1) // 0 is connected to coreClock
    }

    // Reset net distribution
    corePsr.PeripheralReset --> resetPins
    periphPsr.PeripheralAResetN --> Seq(mmioSMC.ARESETN, dmaSMC.ARESETN)
    uartOpt.foreach(uart => periphPsr.PeripheralAResetN --> uart.S_AXI_ARESETN)

    corePsr.PeripheralAResetN --> memPaths.map(_.ddr4Inst.C0_DDR4_ARESETN)
    corePsr.PeripheralAResetN --> memPaths.map(_.memSMC.ARESETN)

    // --------------------------------------------------------------------------
    // Interrupt wiring
    // --------------------------------------------------------------------------
    if (irqIdx > 0) {
      interruptConcat --> top.INTERRUPTS
    } else {
      TieOff() --> top.INTERRUPTS
    }

    uartDTSOpt.foreach { dts =>
      dts.irqs.foreach { irq =>
        uartOpt.get.INTERRUPT --> interruptConcat.IN(irq.index)
      }
    }

    // --------------------------------------------------------------------------
    // AXI wiring (discover the exported AXI4 ports from the SOCT system)
    // --------------------------------------------------------------------------
    memPaths.foreach { path =>
      path.memSMC.M_AXI(0) <-> path.ddr4Inst.C0_DDR4_S_AXI
      path.memSMC.S_AXI(0) <-> path.ddr4Info.mAxi.bdPin
    }

    // MMIO path: Rocket MMIO -> mmioSMC -> (UART + SD-lite)
    mmioSMC.S_AXI(0) <-> axiMMIO
    uartOpt.foreach(uart => mmioSMC.M_AXI(1) <-> uart.S_AXI)

    // DMA path: SD DMA -> dmaSMC -> Rocket L2 frontend
    dmaSMC.M_AXI(0) <-> axiDMA

    // --------------------------------------------------------------------------
    // Optional SDCard PMOD
    // --------------------------------------------------------------------------
    if (p(HasSDCardPMOD).isDefined) {
      val sdPMODPort = p(HasSDCardPMOD).get
      val sdPmod = SDCardPMOD(dtsInfo = sdDTSOpt.get, getAxiMasterPin = axiMMIO,
        getAxiSlavePins = Seq((axiDMA, "reg0")))

      val (sdioCd, sdioClk, sdioCmd, sdioData) = (SDIOCDPort(sdPMODPort), SDIOClkPort(sdPMODPort), SDIOCmdPort(sdPMODPort), SDIODataPort(sdPMODPort))
      val ports = Seq(sdioCd, sdioClk, sdioCmd, sdioData)

      peripheryClock --> sdPmod.CLOCK
      periphPsr.PeripheralAResetN --> sdPmod.ASYNC_RESETN

      sdPmod <-> ports

      dmaSMC.S_AXI(0) <-> sdPmod.M_AXI
      mmioSMC.M_AXI(0) <-> sdPmod.S_AXI

      sdDTSOpt.foreach { sdDTS =>
        sdDTS.irqs.foreach { irq =>
          sdPmod.INTERRUPT --> interruptConcat.IN(irq.index)
        }
      }


      bd.addTimingConstraints(() => Seq(
        s"""# Timing constraints for SDCardPMOD (${sdPmod.instanceName})
           |set sdio_clock [get_clocks -of_objects [get_pins -hier ${sdPmod.CLOCK.ref}]]
           |
           |set_max_delay -from $$sdio_clock -to [get_ports {${sdioClk.portName} ${sdioCmd.portName} ${sdioData.portName}*}] -datapath_only 8.0
           |set_max_delay -from [get_ports {${sdioCmd.portName} ${sdioData.portName}*}] -to $$sdio_clock -datapath_only 8.0
           |set_min_delay -from [get_ports {${sdioCd.portName} ${sdioCmd.portName} ${sdioData.portName}*}] -to $$sdio_clock 0.0
           |
           |set_max_delay -from [get_ports ${sdioCd.portName}] -to $$sdio_clock -datapath_only 100.0
           |set_max_delay -from $$sdio_clock -through [get_pins -hier ${sdPmod.INTERRUPT.ref}] -datapath_only 10.0
           |""".stripMargin.tcl
      ))
    }

    // --------------------------------------------------------------------------
    // Debug / SystemJTAG integration
    // --------------------------------------------------------------------------
    if (debug.isDefined) {
      val debugIf = debug.getWrappedValue.get

      coreClock --> debugIf.clock
      corePsr.PeripheralReset --> debugIf.reset
      portToBdPin(debugIf.dmactiveAck) --> portToBdPin(debugIf.dmactive)

      if (debugIf.systemjtag.isDefined) {
        val jtagIO = debugIf.systemjtag.get
        val jtag = jtagIO.jtag
        TieOff().withInstanceName("jtag_io_reset_tieoff") --> jtagIO.reset

        // Create TDT signal for Vivado JTAG integration - TDO is driven when TDT is low
        val jtag_tdt = IO(Output(Bool())).suggestName("jtag_tdt")
        jtag_tdt := ~jtag.TDO.driven

        val jtagXIntf = JTAGIntf(jtag, jtag_tdt)

        // Tie off unused fields using inline constants - rename for clarity in block design
        val mfrIdConst = InlineConstant("b10010001001".U, jtagIO.mfr_id.getWidth).withInstanceName("jtag_mfr_id_constant")
        mfrIdConst --> jtagIO.mfr_id

        val partNumConst = InlineConstant(0.U, jtagIO.part_number.getWidth).withInstanceName("jtag_part_number_constant")
        partNumConst --> jtagIO.part_number

        val versionConst = InlineConstant(0.U, jtagIO.version.getWidth).withInstanceName("jtag_version_constant")
        versionConst --> jtagIO.version

        val bscan = BSCAN()
        val b2j = BSCAN2JTAG()
        bscan <-> b2j
        b2j <-> jtagXIntf

        // JTAG / Debug Bridge timing constraints. If a TCK pin exists on the
        // SERIES7_BSCAN cell, create a 15ns jtag_clock (if Vivado hasn't already
        // inferred one) and bound the core<->JTAG CDC. The `-reset_path` flag
        // tells Vivado these paths are used only for debug-reset purposes and
        // shouldn't be analyzed as functional timing.
        bd.addTimingConstraints(() => Seq(
          s"""# JTAG / Debug Bridge timing constraints
             |set tck_pin ""
             |if { [llength [get_pins -quiet -hier SERIES7_BSCAN*/TCK]] } {
             |  set tck_pin [get_pins -hier SERIES7_BSCAN*/TCK]
             |}
             |if { $$tck_pin != "" } {
             |  if { ![llength [get_clocks -quiet -of_objects $$tck_pin]] } {
             |    create_clock -name jtag_clock -period 15.000 $$tck_pin
             |  }
             |  set jtag_clock [get_clocks -of_objects $$tck_pin]
             |  set jtag_clock_period [get_property -min PERIOD $$jtag_clock]
             |
             |  set_max_delay -reset_path -from $$$coreClockObj -to $$jtag_clock -datapath_only $$jtag_clock_period
             |  set_max_delay -reset_path -from $$jtag_clock -to $$$coreClockObj -datapath_only $$$corePeriodProp
             |}
             |""".stripMargin.tcl
        ))
      }
    }

    // --------------------------------------------------------------------------
    // Final wiring and tie-offs
    // --------------------------------------------------------------------------
    resetctrl.foreach { r =>
      r.hartIsInReset.zipWithIndex.foreach { case (h, i) =>
        TieOff().withInstanceName(s"reset_tieoff_$i") --> h
      }
    }
  }
}