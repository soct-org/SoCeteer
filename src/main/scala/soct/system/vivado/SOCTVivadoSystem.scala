package soct.system.vivado

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.InModuleBody
import soct.system.soceteer.SOCTSystem
import soct.{BdBuilderKey, HasDDR4ExtMem, HasSDCardPMOD, PeripheryClockDomain, XilinxFPGAKey, log}
import soct.system.vivado.components.{AND, AXISmartConnect, AXIUartLite, BSCAN, BSCAN2JTAG, ClkWiz, DDR4, InlineConcat, InlineConstant, OR, ProcSysReset, SDCardPMOD, SDIOCDPort, SDIOClkPort, SDIOCmdPort, SDIODataPort, SDIOPort, SOCTVivadoSystemTop}
import soct.system.vivado.fpga.{FPGAClockDomain, FPGARegistry}
import soct.system.vivado.abstracts._
import soct.system.vivado.intf.{AXIMM, JTAGIntf}


/**
 * Top-level module for synthesis of the RocketSystem within SOCT using Vivado
 */
class SOCTVivadoSystem(implicit p: Parameters) extends SOCTSystem {

  if (p(BdBuilderKey).isDefined) {
    implicit val bd: SOCTBdBuilder = p(BdBuilderKey).get
    require(p(HasDDR4ExtMem), "SOCTVivadoSystem currently requires HasDDR4ExtMem to be set in parameters.")

    InModuleBody {
      val fpga = FPGARegistry.resolveBoardInstance(p(XilinxFPGAKey).get)
      val FPGAClockDomain(fpgaClk, fpgaRst, _) = fpga.fastestClock
      val top = new SOCTVivadoSystemTop(this)

      bd.init(p, top, fpga)

      val peripheryDomain = new ClockDomain(freqMHz = p(PeripheryClockDomain), tclVarName = Some("$periphery_clk_freq"))
      val coreDomain = new ClockDomain(100.0, tclVarName = Some("$core_clk_freq")) // Default to 100 MHz - TODO use parameters
      val ddr4OutDomain = new ClockDomain(freqMHz = coreDomain.freqMHz) // TODO: Currently uses core frequency for DDR4 clock wizard - can/should we drive it as fast as possible instead?

      // Ports
      val uartPort = fpga.initUARTPort()
      val ddr4Port = fpga.initDDR4Port()

      // Components
      val periphPsr = ProcSysReset().withInstanceName("periph_psr")
      val corePsr = ProcSysReset().withInstanceName("core_psr")
      val ddrPsr = ProcSysReset().withInstanceName("ddr_psr")
      val clkWiz = ClkWiz()
      val uart = AXIUartLite()
      val ddr4 = DDR4()
      val memSMC = AXISmartConnect().withInstanceName("mem_smc")
      val mmioSMC = AXISmartConnect().withInstanceName("mmio_smc")
      val dmaSMC = AXISmartConnect().withInstanceName("dma_smc")
      val interruptConcat = InlineConcat(nExtInterrupts)

      // Pins
      val ddr4Clk1 = ddr4.ADDN_UI_CLKOUT(1, ddr4OutDomain)
      val peripheryClock = clkWiz.CLK_OUT(1, peripheryDomain)
      val coreClock = clkWiz.CLK_OUT(2, coreDomain)

      // Connections
      ddr4 <-> ddr4Port
      ddr4Clk1 --> clkWiz.CLK_IN(1)
      uart.UART <-> uartPort
      ddr4.C0_DDR4_UI_CLK --> ddrPsr.SLOWEST_SYNC_CLK


      fpgaClk --> ddr4.C0_SYS_CLK
      fpgaRst --> Seq(ddr4.SYS_RST, clkWiz.RESET, periphPsr.EXT_RESET_IN, corePsr.EXT_RESET_IN)

      OR(1, fpgaRst, ddr4.C0_DDR4_UI_CLK_SYNC_RST) --> ddrPsr.EXT_RESET_IN

      clkWiz.LOCKED --> Seq(periphPsr.DCM_LOCKED, corePsr.DCM_LOCKED)
      // TODO make sure its the slowest clock if we add more clock domains
      peripheryClock --> Seq(periphPsr.SLOWEST_SYNC_CLK, mmioSMC.ACLK(0), dmaSMC.ACLK(0), uart.S_AXI_ACKL)

      periphPsr.PeripheralReset --> top.RESETS
      coreClock --> top.CLOCKS
      ddr4.C0_DDR4_UI_CLK --> memSMC.ACLK(1)
      coreClock --> Seq(memSMC.ACLK(0), corePsr.SLOWEST_SYNC_CLK)

      periphPsr.PeripheralAResetN --> Seq(mmioSMC.ARESETN, dmaSMC.ARESETN, uart.S_AXI_ARESETN)
      ddrPsr.PeripheralAResetN --> ddr4.C0_DDR4_ARESETN
      // The memSMC reset is influenced by both the core and DDR resets since it interfaces between them - it should be held in reset if either domain is in reset, and only released when both are out of reset
      AND(1, corePsr.PeripheralAResetN, ddrPsr.PeripheralAResetN) --> memSMC.ARESETN

      interruptConcat.DOUT --> top.INTERRUPTS
      uart.INTERRUPT --> interruptConcat.IN(0)

      val axiMem = Seq(mem_axi4).flatten.map { axi4 => AXIMM(axi4) }.headOption.getOrElse(
        throw new XilinxDesignException("No memory-mapped AXI4 port found for memory interface in SOCT system.")
      )
      val axiMMIO = Seq(mmio_axi4).flatten.map { axi4 => AXIMM(axi4) }.headOption.getOrElse(
        throw new XilinxDesignException("No memory-mapped AXI4 port found for MMIO interface in SOCT system.")
      )
      val axiL2Frontend = Seq(l2_frontend_bus_axi4).flatten.map { axi4 => AXIMM(axi4) }.headOption.getOrElse(
        throw new XilinxDesignException("No memory-mapped AXI4 port found for L2 frontend interface in SOCT system.")
      )


      memSMC.S_AXI(0) <-> axiMem
      memSMC.M_AXI(0) <-> ddr4.C0_DDR4_S_AXI

      mmioSMC.S_AXI(0) <-> axiMMIO

      dmaSMC.M_AXI(0) <-> axiL2Frontend

      mmioSMC.M_AXI(1) <-> uart.S_AXI

      if (p(HasSDCardPMOD).isDefined) {
        val sdPmod = SDCardPMOD(pmodIdx = p(HasSDCardPMOD).get)
        val ports: Seq[SDIOPort] = Seq(SDIOCDPort(), SDIOClkPort(), SDIOCmdPort(), SDIODataPort())
        peripheryClock --> sdPmod.CLOCK
        periphPsr.PeripheralAResetN --> sdPmod.ASYNC_RESETN
        sdPmod <-> ports
        dmaSMC.S_AXI(0) <-> sdPmod.M_AXI
        mmioSMC.M_AXI(0) <-> sdPmod.S_AXI_LITE
        sdPmod.INTERRUPT --> interruptConcat.IN(1)
      }

      val debugIf = debug.getWrappedValue.get

      if (debugIf.systemjtag.isDefined) {
        // Create TDT signal for Vivado JTAG integration - TDO is driven when TDT is low
        val jtagIO = debugIf.systemjtag.get
        val jtag = jtagIO.jtag
        val jtag_tdt = IO(Output(Bool())).suggestName("jtag_tdt")
        jtag_tdt := ~jtag.TDO.driven
        val jtagXIntf = JTAGIntf(jtag, jtag_tdt)

        // Tie off unused fields using inline constants - rename for clarity in block design
        val mfrIdConst = new InlineConstant("b10010001001".U, jtagIO.mfr_id.getWidth) {
          override val friendlyName: String = "jtag_mfr_id_constant"
        }
        mfrIdConst --> jtagIO.mfr_id

        val partNumConst = new InlineConstant(0.U, jtagIO.part_number.getWidth) {
          override val friendlyName: String = "jtag_part_number_constant"
        }
        partNumConst --> jtagIO.part_number

        val versionConst = new InlineConstant(0.U, jtagIO.version.getWidth) {
          override val friendlyName: String = "jtag_version_constant"
        }
        versionConst --> jtagIO.version

        val bscan = BSCAN()
        val b2j = BSCAN2JTAG()
        bscan <-> b2j
        b2j <-> jtagXIntf
      }
    }
  }
  else {
    log.info("No BDBuilder found in parameters - skipping block design generation and only elaborating Chisel design. " +
      "If you intended to use Vivado, please ensure HasBdBuilder is set in the parameters.")
  }
}