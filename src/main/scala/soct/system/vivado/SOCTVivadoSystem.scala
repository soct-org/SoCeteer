package soct.system.vivado

import chisel3._
import freechips.rocketchip.resources.ResourceInt
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.InModuleBody
import soct._
import soct.system.soceteer.SOCTSystem
import soct.system.vivado.abstracts.BdPinPort.portToBdPin
import soct.system.vivado.abstracts._
import soct.system.vivado.components._
import soct.system.vivado.fpga.{FPGAClockDomain, FPGARegistry}
import soct.system.vivado.intf.JTAGIntf
import soct.system.vivado.misc.{AxiSlaveBinder, DTSInfo, Irq}

/**
 * Top-level module for synthesis of the RocketSystem within SOCT using Vivado
 */
class SOCTVivadoSystem(implicit p: Parameters) extends SOCTSystem {

  implicit val bd: SOCTBdBuilder = p(BdBuilderKey).getOrElse(
    throw new XilinxDesignException("SOCTVivadoSystem requires a BdBuilder to be set in parameters for block design generation.")
  )

  require(p(HasDDR4ExtMem), "SOCTVivadoSystem currently requires HasDDR4ExtMem to be set in parameters.")

  //-------------------------------------------------------------------------
  // Device tree generation
  // (must be done before module instantiation since some components bind resources during construction)
  //-------------------------------------------------------------------------
  val plicDev = plicOpt.getOrElse(
    throw new XilinxDesignException("SOCTVivadoSystem requires a PLIC to be present in the system for interrupt wiring.")
  ).device

  val uartDTS = DTSInfo(
    parent = mmioBusDevice.get,
    regs = Seq(("reg", 0x60010000L, 0x10000L)),
    irqs = Seq(Irq(plicDev, 0)),
    compatibles = Seq("riscv,axi-uart-1.0"),
    extraProps = Map("port-number" -> Seq(ResourceInt(0)))
  )
  AxiSlaveBinder.bindSimpleDevice(
    devname = "uart0",
    dts = uartDTS,
    perms = AxiSlaveBinder.mmioPerms
  )

  val sdDTSOpt = p(HasSDCardPMOD).map { idx =>
    val sdDTS = DTSInfo(
      parent = mmioBusDevice.get,
      regs = Seq(("reg", 0x60000000L, 0x10000L)),
      irqs = Seq(Irq(plicDev, 1)),
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
    val fpga = FPGARegistry.resolveBoardInstance(p(XilinxFPGAKey).get)
    val FPGAClockDomain(fpgaClk, fpgaRst, _) = fpga.fastestClock

    val top = new SOCTVivadoSystemTop(this)
    bd.init(p, top, fpga)
    // The axi ports:
    val Seq(axiMem, axiMMIO, axiL2Frontend) = top.axi4BusMapping.map(_.bdPin)

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

    // TODO: currently uses core frequency for DDR4 clock wizard - can/should we drive it faster?
    val ddr4OutDomain = new ClockDomain(freqMHz = coreDomain.freqMHz)

    // --------------------------------------------------------------------------
    // Board ports
    // --------------------------------------------------------------------------
    val uartPort = fpga.initUARTPort()
    val ddr4Port = fpga.initDDR4Port()

    // --------------------------------------------------------------------------
    // Components
    // --------------------------------------------------------------------------
    val clkWiz = ClkWiz()
    val rstSync = ResetSyncStretch()
    val memRstCtrl = MemResetControl()

    val uart = AXIUartLite(dtsInfo = uartDTS, getAxiMasterPin = axiMMIO)
    val ddr4 = DDR4(axiMem)

    val memSMC = AXISmartConnect().withInstanceName("mem_smc")
    val mmioSMC = AXISmartConnect().withInstanceName("mmio_smc")
    val dmaSMC = AXISmartConnect().withInstanceName("dma_smc")

    val interruptConcat = InlineConcat(2)

    // --------------------------------------------------------------------------
    // Derived pins (clock outputs / DDR helper pins)
    // --------------------------------------------------------------------------
    val ddr4Clk1 = ddr4.ADDN_UI_CLKOUT(1, ddr4OutDomain)
    val peripheryClock = clkWiz.CLK_OUT(1, peripheryDomain)
    val coreClock = clkWiz.CLK_OUT(2, coreDomain)

    // Timing constraints
    lazy val (coreClockTCL, coreClockObj, corePeriodProp) = clkWiz.timingTcl(2, "core_clock")
    bd.addTimingConstraints(() => coreClockTCL)
    bd.addTimingConstraints(() => ddr4.timingTcl(coreClockObj, corePeriodProp))

    // --------------------------------------------------------------------------
    // Fundamental interconnect (interfaces & major clocks and resets)
    // --------------------------------------------------------------------------
    val memOk = memRstCtrl.MEM_OK
    val clockOk = clkWiz.LOCKED
    // TODO add ioOk later

    ddr4 <-> ddr4Port
    uart.UART <-> uartPort

    // DDR4 -> drive clock wizard input from DDR addn clkout
    ddr4Clk1 --> clkWiz.CLK_IN(1)

    // Board clocks/resets into IPs
    fpgaClk --> ddr4.C0_SYS_CLK

    fpgaRst --> Seq(
      clkWiz.RESET,
      memRstCtrl.SYS_RESET
    )

    // Periphery domain clock drives periph reset sync + periph-ish IP clocks
    peripheryClock --> Seq(
      mmioSMC.ACLK(0),
      dmaSMC.ACLK(0),
      uart.S_AXI_ACLK,
      memRstCtrl.CLOCK
    )

    memRstCtrl.ARESETN --> ddr4.C0_DDR4_ARESETN

    memRstCtrl.CLOCK_OK <-- clockOk

    coreClock --> Seq(
      rstSync.CLOCK,
      memSMC.ACLK(0),
      mmioSMC.ACLK(1),
      dmaSMC.ACLK(1), // TODO: use periphery clock for this?
    )
    coreClock --> clockPins

    ddr4.C0_DDR4_UI_CLK --> Seq(memSMC.ACLK(1), memRstCtrl.UI_CLK)
    ddr4.CO_INIT_CALIB_COMPLETE --> memRstCtrl.CALIB_COMPLETE
    ddr4.C0_DDR4_UI_CLK_SYNC_RST --> memRstCtrl.UI_CLK_SYNC_RST

    //val anyInvalid = NOT(AND(memOk, clockOk, "mem_ok_and_clock_ok").r, "not_ok").r
    //rstSync.RESET_IN <-- OR(fpgaRst, anyInvalid, "fpga_rst_or_not_ok").r
    rstSync.RESET_IN <-- fpgaRst

    // --------------------------------------------------------------------------
    // Interrupt wiring
    // --------------------------------------------------------------------------
    interruptConcat.DOUT --> top.INTERRUPTS
    uartDTS.irqs.foreach { irq =>
      uart.INTERRUPT --> interruptConcat.IN(irq.index)
    }

    // --------------------------------------------------------------------------
    // AXI wiring (discover the exported AXI4 ports from the SOCT system)
    // --------------------------------------------------------------------------

    // Memory path: Rocket MEM -> memSMC -> DDR S_AXI
    memSMC.S_AXI(0) <-> axiMem
    memSMC.M_AXI(0) <-> ddr4.C0_DDR4_S_AXI

    // MMIO path: Rocket MMIO -> mmioSMC -> (UART + SD-lite)
    mmioSMC.S_AXI(0) <-> axiMMIO
    mmioSMC.M_AXI(1) <-> uart.S_AXI

    // DMA path: SD DMA -> dmaSMC -> Rocket L2 frontend
    dmaSMC.M_AXI(0) <-> axiL2Frontend

    rstSync.ARESETN --> dmaSMC.ARESETN
    rstSync.ARESETN --> mmioSMC.ARESETN
    rstSync.ARESETN --> uart.S_AXI_ARESETN
    memRstCtrl.ARESETN --> memSMC.ARESETN

    TieOff().withInstanceName("mmcm_locked_tieoff").DOUT --> memRstCtrl.MMCM_LOCKED
    memRstCtrl.MEM_RESET --> ddr4.SYS_RST

    // --------------------------------------------------------------------------
    // Optional SDCard PMOD
    // --------------------------------------------------------------------------
    if (p(HasSDCardPMOD).isDefined) {
      val sdPMODPort = p(HasSDCardPMOD).get
      val sdPmod = SDCardPMOD(dtsInfo = sdDTSOpt.get, getAxiMasterPin = axiMMIO,
        getAxiSlavePins = Seq((axiL2Frontend, "reg0")))

      val (sdioCd, sdioClk, sdioCmd, sdioData) = (SDIOCDPort(sdPMODPort), SDIOClkPort(sdPMODPort), SDIOCmdPort(sdPMODPort), SDIODataPort(sdPMODPort))
      val ports = Seq(sdioCd, sdioClk, sdioCmd, sdioData)

      peripheryClock --> sdPmod.CLOCK
      rstSync.ARESETN --> sdPmod.ASYNC_RESETN

      sdPmod <-> ports

      dmaSMC.S_AXI(0) <-> sdPmod.M_AXI
      mmioSMC.M_AXI(0) <-> sdPmod.S_AXI

      sdDTSOpt.foreach { sdDTS =>
        sdDTS.irqs.foreach { irq =>
          sdPmod.INTERRUPT --> interruptConcat.IN(irq.index)
        }
      }

      bd.addTimingConstraints(() => sdPmod.timingTcl(coreClockObj, corePeriodProp, sdioCd, sdioClk, sdioCmd, sdioData))
    }

    // --------------------------------------------------------------------------
    // Debug / SystemJTAG integration
    // --------------------------------------------------------------------------
    val coreReset: BdPinOut = if (debug.isDefined) {
      val debugIf = debug.getWrappedValue.get

      coreClock --> debugIf.clock
      rstSync.RESET --> debugIf.reset
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

        bd.addTimingConstraints(() => bscan.timingTcl(coreClockObj, corePeriodProp))
      }
      OR(rstSync.RESET, portToBdPin(debugIf.ndreset), "sync_reset_or_ndreset").r
    } else {
      rstSync.RESET
    }

    // --------------------------------------------------------------------------
    // Final wiring and tie-offs
    // --------------------------------------------------------------------------
    coreReset --> resetPins
    resetctrl.foreach { r =>
      r.hartIsInReset.zipWithIndex.foreach { case (h, i) =>
        TieOff().withInstanceName(s"reset_tieoff_$i") --> h
      }
    }
  }
}