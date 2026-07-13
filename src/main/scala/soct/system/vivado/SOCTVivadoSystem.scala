package soct.system.vivado

import chisel3._
import freechips.rocketchip.amba.axi4.AXI4SlaveParameters
import freechips.rocketchip.resources.ResourceInt
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.InModuleBody
import soct._
import soct.system.soceteer.SOCTSystem
import soct.system.vivado.abstracts.BdPinPort.portToBdPin
import soct.system.vivado.abstracts._
import soct.system.vivado.components.{AXIAddrDeinterleaver, _}
import soct.system.vivado.fpga.{DDR4PortParams, FPGAClockDomain, UARTPortParams}
import soct.system.vivado.intf.JTAGIntf
import soct.system.vivado.misc.{AXI4BusInfo, AxiSlaveBinder, DTSInfo, Irq}

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
case class DDR4Info(param: DDR4PortParams, ddr4Intf: BdIntfPortMaster, mAxi: AXI4BusInfo,
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
 * Shared base of the Vivado top-level systems: binds the common MMIO devices (UART, SD card)
 * into the device tree and provides TCL helpers for the timing constraints.
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
   * timing-constraint block below to turn pin paths into reusable handles.
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
}


/**
 * Top-level module for synthesis of the RocketSystem within SOCT using Vivado
 */
class SOCTVivadoSystem(implicit p: Parameters) extends SOCTVivadoSystemBase {
  InModuleBody {

    // --------------------------------------------------------------------------
    // Board / Top init
    // --------------------------------------------------------------------------
    val fpga = p(XilinxFPGAKey).getOrElse(throw new VivadoDesignException("XilinxFPGAKey not set in parameters."))
    val FPGAClockDomain(fpgaClk, fpgaRst, _) = fpga.initNClockPorts(1).head

    val top = new SOCTVivadoSystemTop(this)
    bd.init(p, top, fpga)

    val Seq(_axiMems, _axiMMIOs, _axiL2Frontends) = top.axi4BusMapping
    if (_axiMMIOs.size != 1) throw VivadoDesignException(s"Expected exactly one AXI4 MMIO interface but found ${_axiMMIOs.size}")
    if (_axiL2Frontends.size != 1) throw VivadoDesignException(s"Expected exactly one AXI4 DMA interface but found ${_axiL2Frontends.size}")
    val axiMMIO = _axiMMIOs.head.bdPin
    val axiDMA = _axiL2Frontends.head.bdPin
    val extMems = p(RegisteredMems)
    if (!(_axiMems.length == 1 && extMems.length == 1)) throw VivadoDesignException("SOCTVivadoSystem requires exactly one DDR4 memory controller defined.")
    val axiMem = _axiMems.head

    // The Clock and Reset pins from the top
    val clocks = top.ioClocksMapping.values.toSeq
    val clockPins = top.ioClocksMapping.map(_._2.clkPin).toSeq
    val resetPins = top.ioClocksMapping.map(_._2.assocRstPin).toSeq

    // --------------------------------------------------------------------------
    // Clock domains
    // --------------------------------------------------------------------------
    val peripheryDomain = new ClockDomain(
      freq = p(PeripheryClockDomain),
    )

    // TODO Currently, this design only supports a single clock domain for the buses, but we should enable multiple clock domains for different buses in the future.
    val freqs = clocks.flatMap(_.freq).distinct
    if (freqs.size != 1) {
      throw new VivadoDesignException(s"Multiple frequencies ${freqs.mkString(", ")} found for clock bundles ${clocks.map(_.clkPin).mkString(", ")}. This is not currently supported in SOCTVivadoSystem, which only supports a single clock domain for the buses.")
    }
    val coreDomain = new ClockDomain(
      freq = freqs.head,
    )

    // --------------------------------------------------------------------------
    // Board ports
    // --------------------------------------------------------------------------
    val ddr4Param = extMems.head
    val ddr4Info: DDR4Info = DDR4Info(ddr4Param, ddr4Param.initPort, axiMem)

    val uartParamOpt: Option[UARTPortParams] = {
      if (p(HasUART)) {
        if (fpga.uartPorts.isEmpty) {
          throw new VivadoDesignException(s"FPGA ${fpga.friendlyName} does not have any UART ports defined, but HasUART is set to true in parameters.")
        }
        Some(fpga.uartPorts.head)
      } else None
    }

    // --------------------------------------------------------------------------
    // Components
    // --------------------------------------------------------------------------

    val periphPsr = ProcSysReset().withInstanceName("periph_psr")
    val corePsr = ProcSysReset().withInstanceName("core_psr")
    val ddrPsr = ProcSysReset().withInstanceName("ddr_psr")

    val uartOpt = uartParamOpt.map { uartParams =>
      val port = uartParams.initPort
      AXIUartLite(uartDTSOpt.get, axiMMIO, port, uartParams)
    }

    val memPath = MemPath(DDR4(ddr4Info), AXISmartConnect().withInstanceName(s"mem_smc"))

    val mmioSMC = AXISmartConnect().withInstanceName("mmio_smc")
    val dmaSMC = AXISmartConnect().withInstanceName("dma_smc")

    val interruptConcat = InlineConcat(nExtInterrupts)

    // --------------------------------------------------------------------------
    // Derived pins (clock outputs / DDR helper pins)
    // --------------------------------------------------------------------------

    val peripheryClock = memPath.ddr4Inst.ADDN_UI_CLKOUT(1, peripheryDomain)
    val coreClock = memPath.ddr4Inst.ADDN_UI_CLKOUT(2, coreDomain)

    // --------------------------------------------------------------------------
    // Timing constraints
    // --------------------------------------------------------------------------
    val (coreClockTCL, coreClockObj, corePeriodProp) =
      captureClock(coreClock.ref, "core_clock")

    bd.addTimingConstraints(() => coreClockTCL)

    {
      val ddr4 = memPath.ddr4Inst
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
    {
      val ddr4 = memPath.ddr4Inst
      fpgaClk --> ddr4.C0_SYS_CLK
      fpgaRst --> ddr4.SYS_RST
      ddr4.C0_DDR4_UI_CLK --> ddrPsr.SLOWEST_SYNC_CLK
    }
    // --------------------------------------------------------------------------
    // Reset strategy
    // --------------------------------------------------------------------------
    fpgaRst --> ddrPsr.EXT_RESET_IN

    // ndreset from the debug module resets core and periphery but not DDR or JTAG:
    // DDR must not be re-initialized on debug reset; JTAG is separately tied off below.
    if (debug.isDefined && !p(soct.FastPnR)) {
      OR(fpgaRst, portToBdPin(debug.getWrappedValue.get.ndreset))
        .withInstanceName("ndreset_or_sys_rst") --> Seq(periphPsr.EXT_RESET_IN, corePsr.EXT_RESET_IN)
    } else {
      soct.log.info("[FastPnR] The core cannot be reset using a debugger.")
      fpgaRst --> Seq(periphPsr.EXT_RESET_IN, corePsr.EXT_RESET_IN)
    }

    // DDR4 doesn't expose an explicit MMCM-locked pin, but `c0_init_calib_complete`
    // is a superset: it asserts only after the MMCM has locked AND the DRAM init
    // calibration is finished. Using it as DCM_LOCKED conservatively holds the
    // periph and core resets until DDR4 is fully ready — which is what we want
    // anyway, since nothing useful can run before DRAM is up.
    memPath.ddr4Inst.C0_INIT_CALIB_COMPLETE --> Seq(periphPsr.DCM_LOCKED, corePsr.DCM_LOCKED, ddrPsr.DCM_LOCKED)

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
      memPath.memSMC.ACLK(0),
      mmioSMC.ACLK(1),
      dmaSMC.ACLK(1),
    )
    coreClock --> clockPins
    corePsr.PeripheralReset --> resetPins

    memPath.ddr4Inst.C0_DDR4_UI_CLK --> memPath.memSMC.ACLK(1)

    // Reset net distribution (active-low aresetn)
    periphPsr.PeripheralAResetN --> Seq(mmioSMC.ARESETN, dmaSMC.ARESETN)
    uartOpt.foreach(uart => periphPsr.PeripheralAResetN --> uart.S_AXI_ARESETN)
    ddrPsr.PeripheralAResetN --> memPath.ddr4Inst.C0_DDR4_ARESETN

    // memSMC reset is influenced by BOTH core and DDR domains:
    // hold in reset if either domain is in reset, release only when BOTH are out of reset.
    AND(corePsr.PeripheralAResetN, ddrPsr.PeripheralAResetN).withInstanceName("memSMC_reset") --> memPath.memSMC.ARESETN

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
    memPath.memSMC.S_AXI(0) <-> axiMem.bdPin
    memPath.memSMC.M_AXI(0) <-> memPath.ddr4Inst.C0_DDR4_S_AXI

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