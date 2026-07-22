package soct.system.vivado

import chisel3._
import soct._
import soct.SOCTFreq._
import soct.system.vivado.abstracts.BdPinPort.portToBdPin
import soct.system.vivado.abstracts._
import soct.system.vivado.components._
import soct.system.vivado.fpga.{FPGA, FPGAResetPortSource, HasZynqUltraPS, UARTPortParams}
import soct.system.vivado.intf.JTAGIntf
import soct.system.vivado.misc.{AXI4BusInfo, ClkDesc}

/**
 * The component-and-wiring half of [[SOCTVivadoSystemBase]] (one file per concern:
 * device tree in [[SOCTVivadoSystemDTS]], TCL timing helpers in
 * [[SOCTVivadoSystemConstraints]]): [[CommonDesign]] with [[initCommonDesign]], and the
 * `wire*` helpers a concrete system calls from its `InModuleBody`.
 */
trait SOCTVivadoSystemWiring {
  this: SOCTVivadoSystemBase =>

  /**
   * Everything the Vivado systems share, built once by [[initCommonDesign]]: the board and
   * top instance, the exported AXI4 buses, the clock/reset pins and domains, the shared
   * infrastructure components (reset synchronizers, MMIO/DMA SmartConnects, interrupt concat)
   * and the optional UART.
   */
  protected case class CommonDesign(
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
                                     uartOpt: Option[AXIUartLite],
                                     intcOpt: Option[AXIIntc],
                                     sysResetGpio: AxiGpio,
                                   )

  /**
   * Build the topology-independent parts of a Vivado system: look up the board, create and register the top
   * instance, discover the exported AXI4 buses, derive the periphery and core clock domains,
   * and create the shared components (reset synchronizers, MMIO/DMA SmartConnects, interrupt
   * concat, optional UART). Must be called first inside the concrete system's `InModuleBody`.
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

    val peripheryDomain = new ClockDomain(
      freq = p(PeripheryClockDomain),
    )

    // TODO Currently, this design only supports a single clock domain for the buses, but we should enable multiple clock domains for different buses in the future.
    val freqs = clocks.flatMap(_.freq).distinct
    if (freqs.size != 1) {
      throw new VivadoDesignException(s"Multiple frequencies ${freqs.mkString(", ")} found for clock bundles ${clocks.map(_.clkPin).mkString(", ")}. This is not currently supported, only a single clock domain for the buses is.")
    }
    val coreDomain = new ClockDomain(
      freq = freqs.head,
    )

    val uartParamOpt: Option[UARTPortParams] = {
      if (p(HasUART)) {
        if (fpga.uartPorts.isEmpty) {
          throw new VivadoDesignException(s"FPGA ${fpga.friendlyName} does not have any UART ports defined, but HasUART is set to true in parameters.")
        }
        Some(fpga.uartPorts.head)
      } else None
    }

    // Every reset synchronizer gets its own BD hierarchy: the PSR plus its auto-generated
    // fan-out slices are one reset domain's plumbing, collapsed into one block.
    val periphPsr = ProcSysReset().withInstanceName("periph_psr").withGroup("periph_reset")
    val corePsr = ProcSysReset().withInstanceName("core_psr").withGroup("core_reset")
    val mmioSMC = AXISmartConnect().withInstanceName("mmio_smc")
    val dmaSMC = AXISmartConnect().withInstanceName("dma_smc")
    // Sized by the devices that claimed an INTC input, not by NExtTopInterrupts: the core
    // sees a single external interrupt (the INTC's), no matter how many devices exist.
    // Floor of 1 keeps the (then dangling) component constructible in device-less designs.
    val interruptConcat = InlineConcat(math.max(irqIdx, 1))

    val uartOpt = uartParamOpt.map { uartParams =>
      val port = uartParams.initPort
      AXIUartLite(uartDTSOpt.get, axiMMIO, port, uartParams)
    }

    val intcOpt = intcDTSOpt.map { dts =>
      AXIIntc(dts, axiMMIO, nInputs = irqIdx, edgeMask = intcEdgeMask)
        .withInstanceName("fabric_intc")
    }

    val sysResetGpio = AxiGpio(sysResetDTS, axiMMIO, ch1Width = 1, outputs = true)
      .withInstanceName("sys_reset_gpio")

    CommonDesign(fpga, top, axiMems, axiMMIO, axiDMA, clockPins, resetPins,
      peripheryDomain, coreDomain, periphPsr, corePsr, mmioSMC, dmaSMC, interruptConcat,
      uartOpt, intcOpt, sysResetGpio)
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
   * Fan out the periphery clock (periphery reset sync, MMIO/DMA SmartConnects, UART) and
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
    c.uartOpt.foreach(uart => peripheryClock --> uart.S_AXI_ACLK)
    c.intcOpt.foreach(intc => peripheryClock --> intc.S_AXI_ACLK)
    peripheryClock --> c.sysResetGpio.S_AXI_ACLK

    c.periphPsr.PeripheralAResetN --> Seq(c.mmioSMC.ARESETN, c.dmaSMC.ARESETN)
    c.uartOpt.foreach(uart => c.periphPsr.PeripheralAResetN --> uart.S_AXI_ARESETN)
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
   * See [[soct.system.vivado.components.AXIIntc]] for why the PLIC never takes the
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

    uartDTSOpt.foreach { dts =>
      dts.irqs.foreach { irq =>
        c.uartOpt.get.INTERRUPT --> c.interruptConcat.IN(irq.index)
      }
    }
  }

  /**
   * Wire the MMIO path (Rocket MMIO -> mmioSMC -> UART) and the DMA path
   * (dmaSMC -> Rocket L2 frontend).
   *
   * @param c the common design
   */
  protected def wireMmioAndDma(c: CommonDesign): Unit = {
    c.mmioSMC.S_AXI.next() <-> c.axiMMIO
    c.uartOpt.foreach(uart => c.mmioSMC.M_AXI.next() <-> uart.S_AXI)
    c.intcOpt.foreach(intc => c.mmioSMC.M_AXI.next() <-> intc.S_AXI)
    c.mmioSMC.M_AXI.next() <-> c.sysResetGpio.S_AXI
    c.dmaSMC.M_AXI.next() <-> c.axiDMA
  }

  /**
   * Instantiate and wire the optional SD-card PMOD controller ([[HasSDCardPMOD]]): PMOD ports,
   * clock/reset, AXI control and DMA paths, interrupt, and its timing constraints.
   * No-op when the design has no SD card.
   *
   * @param peripheryClock the periphery domain clock pin
   * @param c              the common design
   */
  protected def wireSdCardPmod(peripheryClock: BdPinOut, c: CommonDesign): Unit = {
    if (p(HasSDCardPMOD).isEmpty) return
    val sdPMODPort = p(HasSDCardPMOD).get
    val sdPmod = SDCardPMOD(dtsInfo = sdDTSOpt.get, getAxiMasterPin = c.axiMMIO,
      getAxiSlavePins = Seq((c.axiDMA, "reg0")))

    val (sdioCd, sdioClk, sdioCmd, sdioData) = (SDIOCDPort(sdPMODPort), SDIOClkPort(sdPMODPort), SDIOCmdPort(sdPMODPort), SDIODataPort(sdPMODPort))
    val ports = Seq(sdioCd, sdioClk, sdioCmd, sdioData)

    peripheryClock --> sdPmod.CLOCK
    c.periphPsr.PeripheralAResetN --> sdPmod.ASYNC_RESETN

    sdPmod <-> ports

    c.dmaSMC.S_AXI.next() <-> sdPmod.M_AXI
    c.mmioSMC.M_AXI.next() <-> sdPmod.S_AXI

    sdDTSOpt.foreach { sdDTS =>
      sdDTS.irqs.foreach { irq =>
        sdPmod.INTERRUPT --> c.interruptConcat.IN(irq.index)
      }
    }

    bd.addTimingConstraints(() => Seq(
      s"""# Timing constraints for SDCardPMOD (${sdPmod.bdPath})
         |set sdio_clock [get_clocks -of_objects [get_pins -hier -filter {NAME =~ *${sdPmod.CLOCK.ref}}]]
         |
         |set_max_delay -from $$sdio_clock -to [get_ports {${sdioClk.portName} ${sdioCmd.portName} ${sdioData.portName}*}] -datapath_only 8.0
         |set_max_delay -from [get_ports {${sdioCmd.portName} ${sdioData.portName}*}] -to $$sdio_clock -datapath_only 8.0
         |set_min_delay -from [get_ports {${sdioCd.portName} ${sdioCmd.portName} ${sdioData.portName}*}] -to $$sdio_clock 0.0
         |
         |set_max_delay -from [get_ports ${sdioCd.portName}] -to $$sdio_clock -datapath_only 100.0
         |set_max_delay -from $$sdio_clock -through [get_pins -hier -filter {NAME =~ *${sdPmod.INTERRUPT.ref}}] -datapath_only 10.0
         |""".stripMargin.tcl
    ))
  }

  /**
   * The pixel clock for a video mode. Only modes with standard (CEA-861) pixel clocks are
   * supported; anything else needs its own entry here.
   *
   * @param vs the video parameters
   * @return the pixel clock frequency
   * @throws VivadoDesignException if the mode has no known pixel clock
   */
  private def pixelClockFor(vs: VideoStreamParams): Freq = (vs.width, vs.height, vs.fps) match {
    case (1920, 1080, 60) => 148.5.MHz
    case (1280, 720, 60) => 74.25.MHz
    case _ => throw VivadoDesignException(s"No known pixel clock for video mode ${vs.width}x${vs.height}@${vs.fps}. Add it to SOCTVivadoSystemBase.pixelClockFor.")
  }

  /**
   * Instantiate and wire the DisplayPort video pipeline for Zynq UltraScale+ MPSoC if [[HasVideoStream]] is defined:
   * VDMA (frames from DRAM via the DMA path) -> AXI4-Stream video out (+ timing controller)
   * -> the PS DP controller's live video input. The PS `S_AXI_LPD` port is reachable from the
   * MMIO path through an [[soct.system.vivado.components.AxiAddrOffset]] window, so the
   * RISC-V can program the DP controller. No-op when the design has no video stream.
   *
   * @param coreClock      the core domain clock pin
   * @param peripheryClock the periphery domain clock pin
   * @param c              the common design
   * @throws VivadoDesignException if the video mode has no known pixel clock, or if the
   *                               memory path cannot sustain the mode's frame-fetch bandwidth
   */
  protected def wireVideoStream(coreClock: BdPinOut, peripheryClock: BdPinOut, c: CommonDesign,
                                memPaths: Seq[MemPath]): Unit = {
    val vs = p(HasVideoStream).getOrElse(return)
    val ps = bd.fpgaInstance() match {
      case fpga: HasZynqUltraPS => fpga.getZynqUltraPS()
      case _ => return
    }
    val dts = videoDTSOpt.get

    // Incoherent frame fetch: the VDMA masters the memory-side SmartConnect directly, so it
    // reaches DRAM without crossing the coherent fabric (see [[soct.WithIncoherentVideoStream]]).
    // Only meaningful with exactly one memory channel: with several, memSMC sits behind the
    // address deinterleaver and sees one channel's dense address space, so a framebuffer would
    // have to be pinned to that channel - fail instead of mapping frames to the wrong DRAM.
    val memPathOpt = if (!vs.incoherent) None else Some(memPaths match {
      case Seq(single) => single
      case several => throw VivadoDesignException(
        s"Incoherent video needs exactly one memory channel, but the design has ${several.length}: " +
          "the memory SmartConnect is behind the address deinterleaver and exposes only its own " +
          "channel, so frames would be fetched from the wrong DRAM. Use the coherent video " +
          "pipeline (soct.WithVideoStream) or a single-channel memory layout.")
    })

    // The frame fetch must sustain width x height x fps x 3 B/s through the coherent DMA
    // path (SmartConnect -> L2 frontend, 8 B/cycle on the periphery clock). Measured on the
    // ZCU104 at 100 MHz, that path delivers ~25% of its theoretical rate; demand beyond it
    // starves the video out mid-line and the stream never locks (1080p60 delivered 30 of
    // 60 frames/s). Fail at generation time instead of on the monitor.
    // The incoherent path masters the memory SmartConnect directly, so it is not subject to
    // the coherent port's ordering/in-flight limits - budget it on the core clock instead.
    val streamBytesPerSec = BigInt(vs.width) * vs.height * vs.fps * 3
    val dmaDomain = if (vs.incoherent) c.coreDomain else c.peripheryDomain
    val pathBytesPerSec = BigInt((dmaDomain.freq.toHz * 8 * (if (vs.incoherent) 0.75 else 0.25)).toLong)
    if (streamBytesPerSec > pathBytesPerSec) {
      throw VivadoDesignException(
        s"Video mode ${vs.width}x${vs.height}@${vs.fps} needs $streamBytesPerSec B/s of frame-fetch " +
          s"bandwidth, but the ${if (vs.incoherent) "incoherent" else "coherent"} DMA path sustains only " +
          s"~$pathBytesPerSec B/s at its clock (${dmaDomain.freq}). Use a smaller mode, a faster clock" +
          s"${if (vs.incoherent) "" else ", or the incoherent pipeline (soct.WithIncoherentVideoStream)"}.")
    }

    // Components - the whole PL-side pipeline lives in the `video` BD hierarchy (the PS
    // and the pixel reset synchronizer stay outside: board-level and its own block).
    // Coherent: the frame master targets the Rocket L2-frontend AXI slave. Incoherent: it has
    // no fabric slave to target - its address space is mapped straight onto the DDR4
    // controller's memory segment below, so the DMA never enters the SoC's interconnect.
    val vdma = memPathOpt match {
      case None => AXIVideoDMA(dts.vdma, c.axiMMIO, Seq((c.axiDMA, "reg0"))).withGroup("video")
      case Some(mem) =>
        val ddr4 = mem.ddr4Inst
        new AXIVideoDMA(dts.vdma, c.axiMMIO, Seq.empty) {
          override def assignAddrTcl: TCLCommands = super.assignAddrTcl ++ Seq(
            s"""assign_bd_address -offset 0x00000000 -range 0x${dmaMasterRange.toHexString.toUpperCase} -target_address_space [get_bd_addr_spaces $bdPath/Data_MM2S] [get_bd_addr_segs ${ddr4.bdPath}/C0_DDR4_MEMORY_MAP/C0_DDR4_ADDRESS_BLOCK]
               |# Same 'register' vs 'memory' usage mismatch as the coherent path: re-include the
               |# segment Vivado excluded as a precaution (BD 41-1051).
               |include_bd_addr_seg [get_bd_addr_segs -excluded -of_objects [get_bd_addr_spaces $bdPath/Data_MM2S]]""".stripMargin.tcl
          )
          // An anonymous subclass has no class name to snake-case, so name it explicitly -
          // otherwise the instance (and every path derived from it) degenerates to "_0".
        }.withInstanceName("axivideo_dma").withGroup("video")
    }
    val vtc = VideoTimingController(dts.vtc, c.axiMMIO).withGroup("video")
    val vidOut = AxisVideoOut().withGroup("video")
    val lpdWindow = new AxiAddrOffset(
      getAxiMasterPin = c.axiMMIO, windowBase = 0x7D000000L, windowSize = 0x1000000L, targetBase = 0xFD000000L
    ) {
      override def assignAddrTcl: TCLCommands = {
        // The PS slave segments carry fixed PS addresses; assign them as-is into our master space.
        super.assignAddrTcl ++ Seq(
          s"assign_bd_address -target_address_space [get_bd_addr_spaces ${M_AXI.ref}] [get_bd_addr_segs ${ps.bdPath}/SAXIGP6/*]".tcl
        )
      }
    }.withInstanceName("dp_lpd_window").withGroup("video")

    // Pixel clock: synthesized from the periphery clock, since no board clock matches video rates
    val pixelDomain = new ClockDomain(pixelClockFor(vs))
    val pixClkWiz = ClkWiz(inputFreq = Some(c.peripheryDomain.freq)).withInstanceName("pixel_clk_wiz").withGroup("video")
    peripheryClock --> pixClkWiz.CLK_IN.next()
    c.periphPsr.PeripheralReset --> pixClkWiz.RESET
    val pixelClock = pixClkWiz.CLK_OUT(1, pixelDomain)

    // Clocks: control and memory sides on the periphery domain; the whole video path - the
    // VDMA's pixel stream, the video out, the timing generator and the PS live input - on
    // the pixel domain. The stream must carry one pixel per cycle at the full pixel rate;
    // on the (slower) periphery clock it starves the video out mid-line.
    peripheryClock --> Seq(vdma.S_AXI_LITE_ACLK, vtc.S_AXI_ACLK, ps.SAXI_LPD_ACLK, lpdWindow.ACLK)
    // The frame-fetch master runs in the domain of the SmartConnect it drives: the periphery
    // clock for the coherent path, the core clock for the incoherent one (memSMC's slave side
    // already runs there, so the private port needs no extra SmartConnect clock).
    (if (memPathOpt.isDefined) coreClock else peripheryClock) --> vdma.M_AXI_MM2S_ACLK
    pixelClock --> Seq(vdma.M_AXIS_MM2S_ACLK, vidOut.ACLK, vtc.CLK, vidOut.VID_IO_OUT_CLK,
      ps.DP_VIDEO_IN_CLK)

    // Pixel-domain reset: held while the periphery resets or the pixel MMCM is unlocked.
    // The external reset input MUST be fed active-low here: it arrives through a
    // polarity-stripping slice and Vivado then infers the (read-only) pin polarity as
    // ACTIVE_LOW regardless of the source - feeding the active-high PeripheralReset held
    // this domain in permanent reset (verified on hardware and by C_EXT_RESET_HIGH
    // readback; see the warning on [[soct.system.vivado.components.ProcSysReset]]).
    val pixelPsr = ProcSysReset().withInstanceName("pixel_psr").withGroup("pixel_reset")
    pixelClock --> pixelPsr.SLOWEST_SYNC_CLK
    pixClkWiz.LOCKED --> pixelPsr.DCM_LOCKED
    c.periphPsr.PeripheralAResetN --> pixelPsr.EXT_RESET_IN

    // Resets and enables. The video cores are held out of reset permanently after that:
    // they only produce garbage until the driver programs VDMA/VTC, which is harmless.
    c.periphPsr.PeripheralAResetN --> Seq(vdma.AXI_RESETN, vtc.S_AXI_ARESETN)
    pixelPsr.PeripheralAResetN --> vidOut.ARESETN
    TieHigh().withInstanceName("video_enables_high").withGroup("video") --> Seq(vtc.CLKEN, vtc.RESETN, vidOut.ACLKEN, vidOut.VID_IO_OUT_CE)
    TieOff().withInstanceName("video_ties_low").withGroup("video") --> Seq(vidOut.VID_IO_OUT_RESET, vtc.FSYNC_IN)

    // Stream and timing path
    vdma.M_AXIS_MM2S <-> vidOut.VIDEO_IN
    vtc.VTIMING_OUT <-> vidOut.VTIMING_IN
    vidOut.VTG_CE --> vtc.GEN_CLKEN

    // AXI: control registers + PS register window on the MMIO path, frame reads on the DMA path
    c.mmioSMC.M_AXI.next() <-> vdma.S_AXI
    c.mmioSMC.M_AXI.next() <-> vtc.S_AXI
    c.mmioSMC.M_AXI.next() <-> lpdWindow.S_AXI
    lpdWindow.M_AXI <-> ps.S_AXI_LPD
    memPathOpt match {
      case None => c.dmaSMC.S_AXI.next() <-> vdma.M_AXI
      case Some(mem) => mem.memSMC.S_AXI.next() <-> vdma.M_AXI
    }

    // Video pipeline status readable by software: {bit2 overflow, bit1 underflow,
    // bit0 locked} of the video out - the operational health of the stream (drivers poll
    // locked/underflow to detect starvation).
    val vidStatus = AxiGpio(dts.vidStatus, c.axiMMIO, ch1Width = 3)
      .withInstanceName("video_status_gpio").withGroup("video")
    c.mmioSMC.M_AXI.next() <-> vidStatus.S_AXI
    peripheryClock --> vidStatus.S_AXI_ACLK
    c.periphPsr.PeripheralAResetN --> vidStatus.S_AXI_ARESETN
    val statusBits = InlineConcat(3).withInstanceName("vid_status_concat").withGroup("video")
    vidOut.LOCKED --> statusBits.IN(0)
    vidOut.UNDERFLOW --> statusBits.IN(1)
    vidOut.OVERFLOW --> statusBits.IN(2)
    statusBits --> vidStatus.GPIO_IO_I

    // Interrupt (a level, held until the driver clears DMASR - INTC input configured as
    // level accordingly; the DTS carries it on the VDMA's channel child node)
    vdma.MM2S_INTROUT --> c.interruptConcat.IN(dts.vdmaIrq.index)

    // Parallel video into the PS live input. The PS wants 12 bit per component (36-bit
    // pixel); the stream carries 8 bit per component (24-bit), so each component is padded
    // with 4 zero LSBs. Component order inside the 24-bit word is a software concern (the
    // framebuffer format), not normalized here.
    val padR = InlineSlice(24, 23, 16, 8).withInstanceName("vid_slice_c2").withGroup("video")
    val padG = InlineSlice(24, 15, 8, 8).withInstanceName("vid_slice_c1").withGroup("video")
    val padB = InlineSlice(24, 7, 0, 8).withInstanceName("vid_slice_c0").withGroup("video")
    val zero4 = InlineConstant(0, 4).withInstanceName("vid_pad_zero4").withGroup("video")
    val pixel = InlineConcat(6).withInstanceName("vid_pixel_concat").withGroup("video")

    Seq(padR, padG, padB).foreach(s => vidOut.VID_DATA --> s.DIN)
    padR.DOUT --> pixel.IN(5)
    zero4.DOUT --> Seq(pixel.IN(4), pixel.IN(2), pixel.IN(0))
    padG.DOUT --> pixel.IN(3)
    padB.DOUT --> pixel.IN(1)

    pixel --> ps.DP_LIVE_VIDEO_IN_PIXEL1
    vidOut.VID_ACTIVE_VIDEO --> ps.DP_LIVE_VIDEO_IN_DE
    vidOut.VID_HSYNC --> ps.DP_LIVE_VIDEO_IN_HSYNC
    vidOut.VID_VSYNC --> ps.DP_LIVE_VIDEO_IN_VSYNC
  }

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
