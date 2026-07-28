package soct.system.vivado

import chisel3._
import soct._
import soct.vivado._
import soct.SOCTFreq._
import soct.vivado.abstracts.BdPinPort.portToBdPin
import soct.vivado.abstracts._
import soct.vivado.components._
import soct.system.vivado.features.FeatureWireContext
import soct.vivado.fpga.{FPGA, FPGAResetPortSource, HasZynqUltraPS}
import soct.vivado.intf.JTAGIntf
import soct.vivado.misc.{AXI4BusInfo, ClkDesc}

/**
 * Everything the Vivado systems share, built once by [[SOCTVivadoSystemWiring.initCommonDesign]]:
 * the board and top instance, the exported AXI4 buses, the clock/reset pins and domains, the
 * shared infrastructure components (reset synchronizers, MMIO/DMA SmartConnects, interrupt
 * concat). Top-level so feature modules can take it as a parameter.
 */
case class CommonDesign(
                         fpga: FPGA,
                         top: SOCTVivadoSystemTop,
                         axiMems: Seq[AXI4BusInfo],
                         axiMMIO: BdIntfPin,
                         axiDMA: BdIntfPin,
                         clockPins: Seq[BdChiselPin],
                         resetPins: Seq[BdChiselPin],
                         peripheryDomain: ClockDomain,
                         coreDomain: ClockDomain,
                         periphPsr: ProcSysReset,
                         corePsr: ProcSysReset,
                         mmioSMC: AXISmartConnect,
                         dmaSMC: AXISmartConnect,
                         interruptConcat: InlineConcat,
                         intcOpt: Option[AXIIntc],
                         sysResetGpio: AxiGpio,
                       )

/**
 * The component-and-wiring half of [[SOCTVivadoSystemBase]] (one file per concern:
 * device tree in [[SOCTVivadoSystemDTS]], TCL timing helpers in
 * [[SOCTVivadoSystemConstraints]]): [[CommonDesign]] with [[initCommonDesign]], and the
 * `wire*` helpers a concrete system calls from its `InModuleBody`.
 */
trait SOCTVivadoSystemWiring {
  this: SOCTVivadoSystemBase =>

  /**
   * Build the topology-independent parts of a Vivado system: look up the board, create and register the top
   * instance, discover the exported AXI4 buses, derive the periphery and core clock domains,
   * and create the shared components (reset synchronizers, MMIO/DMA SmartConnects, interrupt
   * concat, feature components). Must be called first inside the concrete system's `InModuleBody`.
   *
   * @return the assembled [[CommonDesign]]
   * @throws VivadoDesignException if no board is set ([[XilinxFPGAKey]]), the top does not
   *                               export exactly one MMIO and one DMA interface, the bus clock
   *                               bundles disagree on their frequency, or HasUART is set but
   *                               the board defines no UART port
   */
  protected def initCommonDesign(): CommonDesign = {
    val fpga = p(XilinxFPGAKey).getOrElse(throw new VivadoDesignException("XilinxFPGAKey not set in parameters."))
    val top = new SOCTVivadoSystemTop(this)
    bd.init(p, top, fpga)

    val Seq(axiMems, _axiMMIOs, _axiL2Frontends) = top.axi4BusMapping
    if (_axiMMIOs.size != 1) throw VivadoDesignException(s"Expected exactly one AXI4 MMIO interface but found ${_axiMMIOs.size}")
    if (_axiL2Frontends.size != 1) throw VivadoDesignException(s"Expected exactly one AXI4 DMA interface but found ${_axiL2Frontends.size}")
    val axiMMIO = _axiMMIOs.head.bdPin
    val axiDMA = _axiL2Frontends.head.bdPin

    // The Clock and Reset pins from the top
    val clocks: Seq[ClkDesc] = top.ioClocksMapping.values.toSeq
    val clockPins = top.ioClocksMapping.map(_._2.clkPin).toSeq
    val resetPins = top.ioClocksMapping.map(_._2.assocRstPin).toSeq

    val peripheryDomain = new ClockDomain(p(PeripheryClockDomain))

    // TODO Currently, this design only supports a single clock domain for the buses, but we should enable multiple clock domains for different buses in the future.
    val freqs = clocks.flatMap(_.freq).distinct
    if (freqs.size != 1) {
      throw new VivadoDesignException(s"Multiple frequencies ${freqs.mkString(", ")} found for clock bundles ${clocks.map(_.clkPin).mkString(", ")}. This is not currently supported, only a single clock domain for the buses is.")
    }
    val coreDomain = new ClockDomain(
      freq = freqs.head,
    )

    // Every reset synchronizer gets its own BD hierarchy: the PSR plus its auto-generated
    // fan-out slices are one reset domain's plumbing, collapsed into one block.
    val periphPsr = ProcSysReset().withInstanceName("periph_psr").withGroup("periph_reset")
    val corePsr = ProcSysReset().withInstanceName("core_psr").withGroup("core_reset")
    val mmioSMC = AXISmartConnect().withInstanceName("mmio_smc")
    val dmaSMC = AXISmartConnect().withInstanceName("dma_smc")
    // Sized by the devices that claimed an INTC input, not by NExtTopInterrupts: the core
    // sees a single external interrupt (the INTC's), no matter how many devices exist.
    // Floor of 1 keeps the (then dangling) component constructible in device-less designs.
    val interruptConcat = InlineConcat(math.max(irqs.count, 1))

    // Feature components, at this fixed point in the creation sequence: same-class
    // instance names and SmartConnect hookups depend on creation order.
    features.foreach(_.createComponents(fpga, axiMMIO))

    val intcOpt = intcDTSOpt.map { dts =>
      AXIIntc(dts, axiMMIO, nInputs = irqs.count, edgeMask = irqs.edgeMask)
        .withInstanceName("fabric_intc")
    }

    val sysResetGpio = AxiGpio(sysResetDTS, axiMMIO, ch1Width = 1, outputs = true)
      .withInstanceName("sys_reset_gpio")

    CommonDesign(fpga, top, axiMems, axiMMIO, axiDMA, clockPins, resetPins,
      peripheryDomain, coreDomain, periphPsr, corePsr, mmioSMC, dmaSMC, interruptConcat,
      intcOpt, sysResetGpio)
  }

  /**
   * Wire the external reset strategy of the core and periphery reset synchronizers:
   * ndreset from the debug module and the software system-reset bit (the syscon-reboot
   * register, see [[sysResetDTS]]) reset core and periphery but not DDR or JTAG
   * (DDR must not be re-initialized on these resets; JTAG is separately tied off).
   *
   * @param fpgaRst the board reset port
   * @param c       the common design
   */
  protected def wireDebugReset(fpgaRst: FPGAResetPortSource, c: CommonDesign): Unit = {
    // Top-level on purpose: these gates span the periphery and core reset domains.
    val hwReset: DrivesNet = if (debug.isDefined) {
      OR(fpgaRst, portToBdPin(debug.getWrappedValue.get.ndreset))
        .withInstanceName("ndreset_or_sys_rst").RES
    } else fpgaRst
    OR(hwReset, c.sysResetGpio.GPIO_IO_O)
      .withInstanceName("sw_or_hw_reset") --> Seq(c.periphPsr.EXT_RESET_IN, c.corePsr.EXT_RESET_IN)
  }

  /**
   * Fan out the periphery clock (periphery reset sync, MMIO/DMA SmartConnects, features) and
   * distribute the periphery active-low resets.
   *
   * @param peripheryClock the periphery domain clock pin
   * @param c              the common design
   */
  protected def wirePeripheryFabric(peripheryClock: BdPinOut, c: CommonDesign): Unit = {
    peripheryClock --> Seq(
      c.periphPsr.SLOWEST_SYNC_CLK,
      c.mmioSMC.ACLK.next(),
      c.dmaSMC.ACLK.next()
    )
    c.intcOpt.foreach(intc => peripheryClock --> intc.S_AXI_ACLK)
    peripheryClock --> c.sysResetGpio.S_AXI_ACLK

    c.periphPsr.PeripheralAResetN --> Seq(c.mmioSMC.ARESETN, c.dmaSMC.ARESETN)
    // Feature clock/reset fan-out at this fixed point: reset edges added here define
    // the reset synchronizer's fan-out slice numbering.
    features.foreach(_.wirePeripheryFabric(peripheryClock, c))
    c.intcOpt.foreach(intc => c.periphPsr.PeripheralAResetN --> intc.S_AXI_ARESETN)
    // Also the self-clearing path of the reboot bit: the reset it raises resets it.
    c.periphPsr.PeripheralAResetN --> c.sysResetGpio.S_AXI_ARESETN
  }

  /**
   * Fan out the core clock (core reset sync, second SmartConnect clocks, the top's clock pins)
   * and drive the top's reset pins from the core reset synchronizer.
   *
   * @param coreClock the core domain clock pin
   * @param c         the common design
   */
  protected def wireCoreFabric(coreClock: BdPinOut, c: CommonDesign): Unit = {
    coreClock --> Seq(
      c.corePsr.SLOWEST_SYNC_CLK,
      c.mmioSMC.ACLK.next(),
      c.dmaSMC.ACLK.next(),
    )
    coreClock --> c.clockPins
    c.corePsr.PeripheralReset --> c.resetPins
  }

  /**
   * Wire the interrupt cascade: the concatenated peripheral interrupts feed the AXI INTC,
   * whose single level output is the core's one external interrupt (or a tie-off when no
   * device raises interrupts); then connect the UART interrupt to its INTC input.
   * See [[soct.vivado.components.AXIIntc]] for why the PLIC never takes the
   * peripherals directly.
   *
   * @param c the common design
   */
  protected def wireInterrupts(c: CommonDesign): Unit = {
    c.intcOpt match {
      case Some(intc) =>
        c.interruptConcat --> intc.INTR
        intc.IRQ --> c.top.INTERRUPTS
      case None =>
        TieOff() --> c.top.INTERRUPTS
    }

    features.foreach(_.wireIrq(c))
  }

  /**
   * Wire the MMIO path (Rocket MMIO -> mmioSMC -> peripherals) and the DMA path
   * (dmaSMC -> Rocket L2 frontend).
   *
   * @param c the common design
   */
  protected def wireMmioAndDma(c: CommonDesign): Unit = {
    c.mmioSMC.S_AXI.next() <-> c.axiMMIO
    // Feature MMIO hookups first: M_AXI.next() allocates master ports in call order,
    // and the UART has always been M00.
    features.foreach(_.wireMmio(c))
    c.intcOpt.foreach(intc => c.mmioSMC.M_AXI.next() <-> intc.S_AXI)
    c.mmioSMC.M_AXI.next() <-> c.sysResetGpio.S_AXI
    c.dmaSMC.M_AXI.next() <-> c.axiDMA
  }

  /**
   * Wire the features' main hookups from the concrete system's `InModuleBody`.
   * ORDER CONTRACT: the wiring order here differs from the [[features]] declaration
   * order on purpose - SmartConnect port numbers, reset-synchronizer fan-out slices and
   * timing-constraint slots are allocated in call order, and this sequence reproduces
   * the historical output (the PS window is wired before the features that use the PS).
   *
   * @param ctx the wiring context
   */
  protected def wireFeatureMains(ctx: FeatureWireContext): Unit =
    Seq(sdFeature, psWindowFeature, usbFeature, videoFeature).flatten.foreach(_.wireMain(ctx))


  /**
   * Wire the debug module and its SystemJTAG interface: debug clock/reset, dmactive feedback,
   * the Vivado JTAG interface with its tie-offs, the BSCAN debug bridge, and the JTAG timing
   * constraints. No-op when the design has no debug module.
   *
   * @param coreClock      the core domain clock pin
   * @param coreClockObj   TCL variable holding the core clock object (see [[registerCoreClockCapture]])
   * @param corePeriodProp TCL variable holding the core clock period
   * @param c              the common design
   */
  protected def wireDebugAndJtag(coreClock: BdPinOut, coreClockObj: String, corePeriodProp: String, c: CommonDesign): Unit = {
    if (debug.isEmpty) return
    val debugIf = debug.getWrappedValue.get

    coreClock --> debugIf.clock
    c.corePsr.PeripheralReset --> debugIf.reset
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

  /**
   * Tie off the per-hart reset inputs of the reset controller, if present.
   */
  protected def tieOffHartResets(): Unit = {
    resetctrl.foreach { r =>
      r.hartIsInReset.zipWithIndex.foreach { case (h, i) =>
        TieOff().withInstanceName(s"reset_tieoff_$i") --> h
      }
    }
  }
}
