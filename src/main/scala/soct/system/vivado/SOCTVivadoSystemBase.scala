package soct.system.vivado

import chisel3._
import freechips.rocketchip.amba.axi4.AXI4SlaveParameters
import freechips.rocketchip.resources.ResourceInt
import org.chipsalliance.cde.config.Parameters
import soct._
import soct.SOCTFreq._
import soct.system.soceteer.SOCTSystem
import soct.system.vivado.abstracts.BdPinPort.portToBdPin
import soct.system.vivado.abstracts._
import soct.system.vivado.components._
import soct.system.vivado.fpga.{DDR4PortParams, FPGA, FPGAResetPortSource, UARTPortParams}
import soct.system.vivado.intf.JTAGIntf
import soct.system.vivado.misc.{AXI4BusInfo, AxiSlaveBinder, ClkDesc, DTSInfo, Irq}

import scala.annotation.unused


/**
 * Information about a DDR4 memory controller and its associated AXI4 bus.
 *
 * @param param         Several parameters describing the DDR4 memory controller, including its name and offset in the memory map
 * @param ddr4Intf      The interface to the board's DDR4 controller
 * @param mAxi          Info about the master's (the processor's) axi interface
 * @param deinterleaver Optional address deinterleaver sitting between the master and the DDR4 controller.
 *                      Present when the memory channels are cache-line interleaved (multi-channel designs):
 *                      it compacts the channel's sparse address view onto a dense range starting at the
 *                      memory base, so the configured channel offset is ignored in that case.
 */
case class DDR4Info(param: DDR4PortParams, ddr4Intf: BdIntfPort, mAxi: AXI4BusInfo,
                    deinterleaver: Option[AXIAddrDeinterleaver] = None) {

  /**
   * The single AXI4 slave the processor-side bus exposes for this memory channel.
   *
   * @return the slave parameters
   * @throws VivadoDesignException if the bus carries no or multiple slaves, or is a master
   */
  @unused // library API
  def slaveParam: AXI4SlaveParameters = mAxi.axiParams.fold(
    sp => {
      if (sp.slaves.length != 1) {
        throw VivadoDesignException(s"AXI4 Slave has ${sp.slaves.length} slaves, but only one is supported.")
      }
      sp.slaves.head
    },
    _ => throw VivadoDesignException(s"AXI4 Slave is not a slave, but a master.")
  )

  /**
   * The AXI address space from which the DDR4 controller's address segment is reached:
   * the deinterleaver's dense-side master port if present, otherwise the processor's port directly.
   */
  def axiAddrSpacePin: BdIntfPin = deinterleaver.map(d => d.M_AXI: BdIntfPin).getOrElse(mAxi.bdPin)

}

/**
 * One memory channel of the design: the DDR4 controller instance and the SmartConnect
 * bridging the processor's memory AXI port (clock-domain crossing + width conversion) to it.
 *
 * @param ddr4Inst the DDR4 controller component
 * @param memSMC   the SmartConnect in front of the controller's S_AXI
 */
case class MemPath(ddr4Inst: DDR4, memSMC: AXISmartConnect)


/**
 * Capability marker for top-level systems that support multiple memory channels
 * (a [[soct.RegisteredMems]] layout with more than one entry). The launcher checks it via
 * reflection on the selected top class (see [[hasMultiMemSupport]]) to pick between the
 * single- and multi-channel memory layout fragments; tops without the marker always get the
 * single-channel layout.
 */
trait SupportsMultiMem


/**
 * Shared base of Vivado top-level systems: binds the common MMIO devices (UART, SD card)
 * into the device tree, builds the topology-independent components and wiring
 * ([[initCommonDesign]] and the `wire*` helpers), and provides TCL helpers for the timing
 * constraints. A concrete system ([[SOCTVivadoSystem]] being the standard one) only adds its
 * memory topology and clock synthesis.
 *
 * @throws VivadoDesignException during construction if no BdBuilder is set in the parameters
 *                               or the system has no PLIC for interrupt wiring
 */
abstract class SOCTVivadoSystemBase(implicit p: Parameters) extends SOCTSystem {
  implicit val bd: SOCTBdBuilder = p(BdBuilderKey).getOrElse(
    throw new VivadoDesignException("SOCTVivadoSystemBase requires a BdBuilder to be set in parameters for block design generation.")
  )

  /**
   * Bind a clock-output pin (by hierarchical path) to a triple of TCL variables:
   *   - `<varBase>`: the pin handle
   *   - `<varBase>_clk`: the `get_clocks` object driving it
   *   - `<varBase>_period`: its min PERIOD
   *
   * Pure TCL plumbing — no topology-specific assumptions baked in. Used by the
   * timing-constraint helpers below to turn pin paths into reusable handles.
   *
   * @param pinPath hierarchical pin path (typically `<instanceName>/<pin>`),
   *                e.g. `s"${ddr4.instanceName}/addn_ui_clkout2"`. Matched with
   *                a leading `*` and `-hier`, so partial paths work.
   * @param varBase base TCL variable name (e.g. `"core_clock"`)
   * @return (TCL commands, clockVarName, periodVarName)
   */
  protected def captureClock(pinPath: String, varBase: String): (TCLCommands, String, String) = {
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
  private val plicDev = plicOpt.getOrElse(
    throw new VivadoDesignException("SOCTVivadoSystemBase requires a PLIC to be present in the system for interrupt wiring.")
  ).device

  /** Next free PLIC interrupt index; bumped for every bound MMIO device. */
  protected var irqIdx = 0

  /** Device-tree entry of the UART, if the design has one ([[HasUART]]). */
  protected val uartDTSOpt: Option[DTSInfo] = if (p(HasUART)) {
    val dts = DTSInfo(
      parent = mmioBusDevice.get,
      regs = Seq(("reg", 0x60010000L, 0x10000L)),
      irqs = Seq(Irq(plicDev, irqIdx)),
      // The soct compatible first (soctglue matches it); the Xilinx one second, so
      // Linux's uartlite driver (SERIAL_UARTLITE) binds the console to this UART.
      compatibles = Seq("riscv,axi-uart-1.0", "xlnx,xps-uartlite-1.00.a"),
      // current-speed is REQUIRED by the Linux uartlite driver (the baud is fixed at
      // synthesis, so the driver refuses to guess): without it the probe fails with
      // -EINVAL and the console never comes up. It must match the IP configuration.
      extraProps = Map(
        "port-number" -> Seq(ResourceInt(0)),
        "current-speed" -> Seq(ResourceInt(115200))
      )
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

  /** Device-tree entry of the SD-card controller, if the design has one ([[HasSDCardPMOD]]). */
  protected val sdDTSOpt: Option[DTSInfo] = p(HasSDCardPMOD).map { idx =>
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

  /** Device-tree entries of the DisplayPort video pipeline (see [[videoDTSOpt]]). */
  protected case class VideoStreamDTS(vdma: DTSInfo, vtc: DTSInfo, dpWindow: DTSInfo,
                                      vidStatus: DTSInfo)

  /**
   * Device-tree entries of the DisplayPort video pipeline, if the design has one
   * ([[HasVideoStream]]): the VDMA control registers, the video timing controller, and the
   * window through which the PS DP registers are reached (see
   * [[soct.system.vivado.components.AxiAddrOffset]]).
   */
  protected val videoDTSOpt: Option[VideoStreamDTS] = p(HasVideoStream).map { vs =>
    val vdmaDTS = DTSInfo(
      parent = mmioBusDevice.get,
      regs = Seq(("reg", 0x60020000L, 0x10000L)),
      irqs = Seq(Irq(plicDev, irqIdx)),
      compatibles = Seq("xlnx,axi-vdma-6.3", "xlnx,axi-vdma-1.00.a")
    )
    irqIdx += 1
    AxiSlaveBinder.bindSimpleDevice(devname = "vdma0", dts = vdmaDTS, perms = AxiSlaveBinder.mmioPerms)

    // The video mode is baked into the design (pixel clock); advertise it so the driver
    // programs matching timing without hardcoding a resolution.
    val vtcDTS = DTSInfo(
      parent = mmioBusDevice.get,
      regs = Seq(("reg", 0x60030000L, 0x10000L)),
      compatibles = Seq("xlnx,v-tc-6.2"),
      extraProps = Map(
        "soct,hactive" -> Seq(ResourceInt(vs.width)),
        "soct,vactive" -> Seq(ResourceInt(vs.height)),
        "soct,fps" -> Seq(ResourceInt(vs.fps))
      )
    )
    AxiSlaveBinder.bindSimpleDevice(devname = "vtc0", dts = vtcDTS, perms = AxiSlaveBinder.mmioPerms)

    // The DP/DPDMA/SERDES registers of the PS, visible through the address-offset window.
    // soct,ps-base carries the fixed PS base the window maps to, so the driver can translate
    // documented PS addresses without magic numbers.
    val dpWindowDTS = DTSInfo(
      parent = mmioBusDevice.get,
      regs = Seq(("reg", 0x7D000000L, 0x1000000L)),
      compatibles = Seq("soct,zynqmp-dp-window"),
      extraProps = Map("soct,ps-base" -> Seq(ResourceInt(0xFD000000L)))
    )
    AxiSlaveBinder.bindSimpleDevice(devname = "dpwin0", dts = dpWindowDTS, perms = AxiSlaveBinder.mmioPerms)

    // Read-only status of the video-out core, which has no register interface of its own:
    // {bit2 overflow, bit1 underflow, bit0 locked} at offset 0x0. Drivers poll locked and
    // underflow to detect a starving or unlocked stream.
    val vidStatusDTS = DTSInfo(
      parent = mmioBusDevice.get,
      regs = Seq(("reg", 0x60040000L, 0x10000L)),
      compatibles = Seq("soct,video-status", "xlnx,xps-gpio-1.00.a")
    )
    AxiSlaveBinder.bindSimpleDevice(devname = "vidstat0", dts = vidStatusDTS, perms = AxiSlaveBinder.mmioPerms)

    VideoStreamDTS(vdmaDTS, vtcDTS, dpWindowDTS, vidStatusDTS)
  }

  //-------------------------------------------------------------------------
  // Common design construction (called from the concrete systems' InModuleBody)
  //-------------------------------------------------------------------------

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

    val periphPsr = ProcSysReset().withInstanceName("periph_psr")
    val corePsr = ProcSysReset().withInstanceName("core_psr")
    val mmioSMC = AXISmartConnect().withInstanceName("mmio_smc")
    val dmaSMC = AXISmartConnect().withInstanceName("dma_smc")
    val interruptConcat = InlineConcat(nExtInterrupts)

    val uartOpt = uartParamOpt.map { uartParams =>
      val port = uartParams.initPort
      AXIUartLite(uartDTSOpt.get, axiMMIO, port, uartParams)
    }

    CommonDesign(fpga, top, axiMems, axiMMIO, axiDMA, clockPins, resetPins,
      peripheryDomain, coreDomain, periphPsr, corePsr, mmioSMC, dmaSMC, interruptConcat, uartOpt)
  }

  //-------------------------------------------------------------------------
  // Shared timing constraints
  //-------------------------------------------------------------------------

  /**
   * Capture the core clock as TCL handles and register the capture commands.
   *
   * @param coreClockRef the pin reference of the core clock output
   * @return (clock object variable name, period variable name) for use in further constraints
   */
  protected def registerCoreClockCapture(coreClockRef: String): (String, String) = {
    val (coreClockTCL, coreClockObj, corePeriodProp) = captureClock(coreClockRef, "core_clock")
    bd.addTimingConstraints(() => coreClockTCL)
    (coreClockObj, corePeriodProp)
  }

  /**
   * Register the timing constraints of one DDR4 controller: false paths on its reset and
   * calibration pins and a bounded CDC between its UI clock and the core clock.
   *
   * @param ddr4           the controller
   * @param coreClockObj   TCL variable holding the core clock object (see [[registerCoreClockCapture]])
   * @param corePeriodProp TCL variable holding the core clock period
   */
  protected def addDdr4TimingConstraints(ddr4: DDR4, coreClockObj: String, corePeriodProp: String): Unit = {
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

  //-------------------------------------------------------------------------
  // Shared wiring helpers
  //-------------------------------------------------------------------------

  /**
   * Wire the external reset strategy of the core and periphery reset synchronizers:
   * ndreset from the debug module resets core and periphery but not DDR or JTAG
   * (DDR must not be re-initialized on debug reset; JTAG is separately tied off).
   *
   * @param fpgaRst the board reset port
   * @param c       the common design
   */
  protected def wireDebugReset(fpgaRst: FPGAResetPortSource, c: CommonDesign): Unit = {
    if (debug.isDefined && !p(soct.FastPnR)) {
      OR(fpgaRst, portToBdPin(debug.getWrappedValue.get.ndreset))
        .withInstanceName("ndreset_or_sys_rst") --> Seq(c.periphPsr.EXT_RESET_IN, c.corePsr.EXT_RESET_IN)
    } else {
      soct.log.info("[FastPnR] The core cannot be reset using a debugger.")
      fpgaRst --> Seq(c.periphPsr.EXT_RESET_IN, c.corePsr.EXT_RESET_IN)
    }
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

    c.periphPsr.PeripheralAResetN --> Seq(c.mmioSMC.ARESETN, c.dmaSMC.ARESETN)
    c.uartOpt.foreach(uart => c.periphPsr.PeripheralAResetN --> uart.S_AXI_ARESETN)
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
   * Wire the external interrupt concat to the top (or tie it off when no device raised an
   * interrupt) and connect the UART interrupt to its PLIC index.
   *
   * @param c the common design
   */
  protected def wireInterrupts(c: CommonDesign): Unit = {
    if (irqIdx > 0) {
      c.interruptConcat --> c.top.INTERRUPTS
    } else {
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
   * Instantiate and wire the DisplayPort video pipeline ([[HasVideoStream]]):
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
  protected def wireVideoStream(coreClock: BdPinOut, peripheryClock: BdPinOut, c: CommonDesign): Unit = {
    val vs = p(HasVideoStream).getOrElse(return)
    val dts = videoDTSOpt.get

    // The frame fetch must sustain width x height x fps x 3 B/s through the coherent DMA
    // path (SmartConnect -> L2 frontend, 8 B/cycle on the periphery clock). Measured on the
    // ZCU104 at 100 MHz, that path delivers ~25% of its theoretical rate; demand beyond it
    // starves the video out mid-line and the stream never locks (1080p60 delivered 30 of
    // 60 frames/s). Fail at generation time instead of on the monitor.
    val streamBytesPerSec = BigInt(vs.width) * vs.height * vs.fps * 3
    val pathBytesPerSec = BigInt((c.peripheryDomain.freq.toHz * 8 * 0.25).toLong)
    if (streamBytesPerSec > pathBytesPerSec) {
      throw VivadoDesignException(
        s"Video mode ${vs.width}x${vs.height}@${vs.fps} needs $streamBytesPerSec B/s of frame-fetch " +
          s"bandwidth, but the DMA path sustains only ~$pathBytesPerSec B/s at the current periphery " +
          s"clock (${c.peripheryDomain.freq}). Use a smaller mode or a faster clock.")
    }

    // Components
    val ps = ZynqUltraPS()
    val vdma = AXIVideoDMA(dts.vdma, c.axiMMIO, Seq((c.axiDMA, "reg0")))
    val vtc = VideoTimingController(dts.vtc, c.axiMMIO)
    val vidOut = AxisVideoOut()
    val lpdWindow = AxiAddrOffset(
      getAxiMasterPin = c.axiMMIO, targetOf = () => ps,
      windowBase = 0x7D000000L, windowSize = 0x1000000L, targetBase = 0xFD000000L
    ).withInstanceName("dp_lpd_window")

    // Pixel clock: synthesized from the periphery clock, since no board clock matches video rates
    val pixelDomain = new ClockDomain(pixelClockFor(vs))
    val pixClkWiz = ClkWiz(inputFreq = Some(c.peripheryDomain.freq)).withInstanceName("pixel_clk_wiz")
    peripheryClock --> pixClkWiz.CLK_IN.next()
    c.periphPsr.PeripheralReset --> pixClkWiz.RESET
    val pixelClock = pixClkWiz.CLK_OUT(1, pixelDomain)

    // Clocks: control and memory sides on the periphery domain; the whole video path - the
    // VDMA's pixel stream, the video out, the timing generator and the PS live input - on
    // the pixel domain. The stream must carry one pixel per cycle at the full pixel rate;
    // on the (slower) periphery clock it starves the video out mid-line.
    peripheryClock --> Seq(vdma.S_AXI_LITE_ACLK, vdma.M_AXI_MM2S_ACLK,
      vtc.S_AXI_ACLK, ps.SAXI_LPD_ACLK, lpdWindow.ACLK)
    pixelClock --> Seq(vdma.M_AXIS_MM2S_ACLK, vidOut.ACLK, vtc.CLK, vidOut.VID_IO_OUT_CLK,
      ps.DP_VIDEO_IN_CLK)

    // Pixel-domain reset: held while the periphery resets or the pixel MMCM is unlocked.
    // The external reset input MUST be fed active-low here: it arrives through a
    // polarity-stripping slice and Vivado then infers the (read-only) pin polarity as
    // ACTIVE_LOW regardless of the source - feeding the active-high PeripheralReset held
    // this domain in permanent reset (verified on hardware and by C_EXT_RESET_HIGH
    // readback; see the warning on [[soct.system.vivado.components.ProcSysReset]]).
    val pixelPsr = ProcSysReset().withInstanceName("pixel_psr")
    pixelClock --> pixelPsr.SLOWEST_SYNC_CLK
    pixClkWiz.LOCKED --> pixelPsr.DCM_LOCKED
    c.periphPsr.PeripheralAResetN --> pixelPsr.EXT_RESET_IN

    // Resets and enables. The video cores are held out of reset permanently after that:
    // they only produce garbage until the driver programs VDMA/VTC, which is harmless.
    c.periphPsr.PeripheralAResetN --> Seq(vdma.AXI_RESETN, vtc.S_AXI_ARESETN)
    pixelPsr.PeripheralAResetN --> vidOut.ARESETN
    TieHigh().withInstanceName("video_enables_high") --> Seq(vtc.CLKEN, vtc.RESETN, vidOut.ACLKEN, vidOut.VID_IO_OUT_CE)
    TieOff().withInstanceName("video_ties_low") --> Seq(vidOut.VID_IO_OUT_RESET, vtc.FSYNC_IN)

    // Stream and timing path
    vdma.M_AXIS_MM2S <-> vidOut.VIDEO_IN
    vtc.VTIMING_OUT <-> vidOut.VTIMING_IN
    vidOut.VTG_CE --> vtc.GEN_CLKEN

    // AXI: control registers + PS register window on the MMIO path, frame reads on the DMA path
    c.mmioSMC.M_AXI.next() <-> vdma.S_AXI
    c.mmioSMC.M_AXI.next() <-> vtc.S_AXI
    c.mmioSMC.M_AXI.next() <-> lpdWindow.S_AXI
    lpdWindow.M_AXI <-> ps.S_AXI_LPD
    c.dmaSMC.S_AXI.next() <-> vdma.M_AXI

    // Video pipeline status readable by software: {bit2 overflow, bit1 underflow,
    // bit0 locked} of the video out - the operational health of the stream (drivers poll
    // locked/underflow to detect starvation).
    val vidStatus = AxiGpio(dts.vidStatus, c.axiMMIO, ch1Width = 3)
      .withInstanceName("video_status_gpio")
    c.mmioSMC.M_AXI.next() <-> vidStatus.S_AXI
    peripheryClock --> vidStatus.S_AXI_ACLK
    c.periphPsr.PeripheralAResetN --> vidStatus.S_AXI_ARESETN
    val statusBits = InlineConcat(3).withInstanceName("vid_status_concat")
    vidOut.LOCKED --> statusBits.IN(0)
    vidOut.UNDERFLOW --> statusBits.IN(1)
    vidOut.OVERFLOW --> statusBits.IN(2)
    statusBits --> vidStatus.GPIO_IO_I

    // Interrupt
    dts.vdma.irqs.foreach { irq =>
      vdma.MM2S_INTROUT --> c.interruptConcat.IN(irq.index)
    }

    // Parallel video into the PS live input. The PS wants 12 bit per component (36-bit
    // pixel); the stream carries 8 bit per component (24-bit), so each component is padded
    // with 4 zero LSBs. Component order inside the 24-bit word is a software concern (the
    // framebuffer format), not normalized here.
    val padR = InlineSlice(24, 23, 16, 8).withInstanceName("vid_slice_c2")
    val padG = InlineSlice(24, 15, 8, 8).withInstanceName("vid_slice_c1")
    val padB = InlineSlice(24, 7, 0, 8).withInstanceName("vid_slice_c0")
    val zero4 = InlineConstant(0, 4).withInstanceName("vid_pad_zero4")
    val pixel = InlineConcat(6).withInstanceName("vid_pixel_concat")

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
