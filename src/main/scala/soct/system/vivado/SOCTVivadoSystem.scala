package soct.system.vivado

import chisel3._
import freechips.rocketchip.resources.ResourceInt
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.InModuleBody
import soct.system.soceteer.SOCTSystem
import soct.system.vivado.abstracts._
import soct.system.vivado.components._
import soct.system.vivado.fpga.{FPGAClockDomain, FPGARegistry}
import soct.system.vivado.intf.{AXIMM, JTAGIntf}
import soct.system.vivado.misc.{AxiSlaveBinder, DTSInfo, Irq}
import soct._

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
      tclVarName = Some("$periphery_clk_freq")
    )

    // TODO Currently, this design only supports a single clock domain for the buses, but we should enable multiple clock domains for different buses in the future.
    val freqs = clocks.flatMap(_.freqHz).distinct
    if (freqs.size != 1) {
      throw new XilinxDesignException(s"Multiple frequencies ${freqs.mkString(", ")} found for clock bundles ${clocks.map(_.clkPin).mkString(", ")}. This is not currently supported in SOCTVivadoSystem, which only supports a single clock domain for the buses.")
    }
    val coreDomain = new ClockDomain(
      freqMHz = freqs.head.toDouble / 1e6, // Convert from Hz to MHz
      tclVarName = Some("$core_clk_freq")
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

    val periphPsr = ProcSysReset().withInstanceName("periph_psr")
    val corePsr = ProcSysReset().withInstanceName("core_psr")
    val ddrPsr = ProcSysReset().withInstanceName("ddr_psr")

    val uart = AXIUartLite(dtsInfo = uartDTS, getAxiMasterPin = axiMMIO)
    val ddr4 = DDR4(axiMem)

    val memSMC = AXISmartConnect().withInstanceName("mem_smc")
    val mmioSMC = AXISmartConnect().withInstanceName("mmio_smc")
    val dmaSMC = AXISmartConnect().withInstanceName("dma_smc")

    val interruptConcat = InlineConcat(nExtInterrupts)

    // --------------------------------------------------------------------------
    // Derived pins (clock outputs / DDR helper pins)
    // --------------------------------------------------------------------------
    val ddr4Clk1 = ddr4.ADDN_UI_CLKOUT(1, ddr4OutDomain)
    val peripheryClock = clkWiz.CLK_OUT(1, peripheryDomain)
    val coreClock = clkWiz.CLK_OUT(2, coreDomain)

    // --------------------------------------------------------------------------
    // Fundamental interconnect (interfaces & major clocks)
    // --------------------------------------------------------------------------
    ddr4 <-> ddr4Port
    uart.UART <-> uartPort

    // DDR4 -> drive clock wizard input from DDR addn clkout
    ddr4Clk1 --> clkWiz.CLK_IN(1)

    // DDR reset domain clocking
    ddr4.C0_DDR4_UI_CLK --> ddrPsr.SLOWEST_SYNC_CLK

    // Board clocks/resets into IPs
    fpgaClk --> ddr4.C0_SYS_CLK
    fpgaRst --> Seq(
      ddr4.SYS_RST,
      clkWiz.RESET,
      periphPsr.EXT_RESET_IN,
      corePsr.EXT_RESET_IN
    )

    // --------------------------------------------------------------------------
    // Reset strategy
    // --------------------------------------------------------------------------
    // DDR PSR should assert reset if either:
    //  - board reset is active, OR
    //  - DDR UI clock domain provides a sync reset signal
    OR(1, fpgaRst, ddr4.C0_DDR4_UI_CLK_SYNC_RST) --> ddrPsr.EXT_RESET_IN

    // Clock wizard lock feeds reset synchronizers for domains derived from it
    clkWiz.LOCKED --> Seq(periphPsr.DCM_LOCKED, corePsr.DCM_LOCKED)

    // Domain clocks:
    // Periphery domain clock drives periph reset sync + periph-ish IP clocks
    peripheryClock --> Seq(
      periphPsr.SLOWEST_SYNC_CLK,
      mmioSMC.ACLK(0),
      dmaSMC.ACLK(0),
      uart.S_AXI_ACLK
    )

    // Core domain clock drives core reset sync + top clocks + one mem SMC clock
    coreClock --> Seq(
      corePsr.SLOWEST_SYNC_CLK,
      memSMC.ACLK(0),
      mmioSMC.ACLK(1),
      dmaSMC.ACLK(1),
    )
    coreClock --> clockPins

    // Memory smartconnect is bridging domains:
    //  - aclk0 = core clock
    //  - aclk1 = DDR UI clock
    ddr4.C0_DDR4_UI_CLK --> memSMC.ACLK(1)

    // Peripheral reset wiring into top aggregators
    periphPsr.PeripheralReset --> resetPins

    // Reset net distribution (active-low aresetn)
    periphPsr.PeripheralAResetN --> Seq(mmioSMC.ARESETN, dmaSMC.ARESETN, uart.S_AXI_ARESETN)
    ddrPsr.PeripheralAResetN --> ddr4.C0_DDR4_ARESETN

    // memSMC reset is influenced by BOTH core and DDR domains:
    // hold in reset if either domain is in reset, release only when BOTH are out of reset.
    AND(1, corePsr.PeripheralAResetN, ddrPsr.PeripheralAResetN) --> memSMC.ARESETN

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

    // --------------------------------------------------------------------------
    // Optional SDCard PMOD
    // --------------------------------------------------------------------------
    if (p(HasSDCardPMOD).isDefined) {
      val sdPMODPort = p(HasSDCardPMOD).get
      val sdPmod = SDCardPMOD(dtsInfo = sdDTSOpt.get, getAxiMasterPin = axiMMIO,
        getAxiSlavePins = Seq((axiL2Frontend, "reg0")))

      val ports = Seq(SDIOCDPort(sdPMODPort), SDIOClkPort(sdPMODPort), SDIOCmdPort(sdPMODPort), SDIODataPort(sdPMODPort))

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
    }

    // --------------------------------------------------------------------------
    // Debug / SystemJTAG integration
    // --------------------------------------------------------------------------
    val debugIf = debug.getWrappedValue.get

    coreClock --> debugIf.clock
    corePsr.PeripheralReset --> debugIf.reset

    if (debugIf.systemjtag.isDefined) {
      val jtagIO = debugIf.systemjtag.get
      val jtag = jtagIO.jtag

      // Create TDT signal for Vivado JTAG integration - TDO is driven when TDT is low
      val jtag_tdt = IO(Output(Bool())).suggestName("jtag_tdt")
      jtag_tdt := ~jtag.TDO.driven

      val jtagXIntf = JTAGIntf(jtag, jtag_tdt)

      // Tie off unused fields using inline constants - rename for clarity in block design
      val mfrIdConst = new InlineConstant("b10010001001".U, jtagIO.mfr_id.getWidth).withInstanceName("jtag_mfr_id_constant")
      mfrIdConst --> jtagIO.mfr_id

      val partNumConst = new InlineConstant(0.U, jtagIO.part_number.getWidth).withInstanceName("jtag_part_number_constant")
      partNumConst --> jtagIO.part_number

      val versionConst = new InlineConstant(0.U, jtagIO.version.getWidth).withInstanceName("jtag_version_constant")
      versionConst --> jtagIO.version

      val bscan = BSCAN()
      val b2j = BSCAN2JTAG()
      bscan <-> b2j
      b2j <-> jtagXIntf
    }
  }
}