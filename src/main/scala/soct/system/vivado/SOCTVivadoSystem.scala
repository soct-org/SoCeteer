package soct.system.vivado

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.InModuleBody
import soct.system.soceteer.SOCTSystem
import soct.{BdBuilderKey, HasDDR4ExtMem, HasSDCardPMOD, PeripheryClockDomain, XilinxFPGAKey, log}
import soct.system.vivado.components.{BSCAN, BSCAN2JTAG, ClkWiz, DDR4, InlineConstant, ProcSysReset, SDCardPMOD, SDIOCDPort, SDIOClkPort, SDIOCmdPort, SDIODataPort, SDIOPort, SOCTVivadoSystemTop}
import soct.system.vivado.fpga.{FPGAClockDomain, FPGARegistry}
import soct.system.vivado.abstracts._
import soct.system.vivado.intf.{AXIMM, JTAGIntf}
import soct.system.vivado.signal.{CLOCK, RESET}


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
      val topInstance = {
        val resetIntf = RESET("SYS_RESET")
        val clockIntf = CLOCK("SYS_CLK")
        new SOCTVivadoSystemTop(this).withRESET(resetIntf).withCLOCK(clockIntf)
      }
      bd.init(p, topInstance, fpga)

      val peripheryDomain = new ClockDomain(freqMHz = p(PeripheryClockDomain), tclVarName = Some("$periphery_clk_freq"))
      val coreDomain = new ClockDomain(100.0, tclVarName = Some("$core_clk_freq")) // Default to 100 MHz - TODO use parameters
      val ddr4OutDomain = new ClockDomain(freqMHz = coreDomain.freqMHz) // TODO: Currently uses core frequency for DDR4 clock wizard - can/should we drive it as fast as possible instead?

      val pcr = ProcSysReset()
      val clkWiz = ClkWiz()
      val ddr4Port = fpga.portsDDR4().headOption.getOrElse(
        throw new XilinxDesignException(s"FPGA ${fpga.friendlyName} does not have any DDR4 ports defined but HasDDR4ExtMem is set in parameters.")
      )
      val ddr4 = DDR4()

      ddr4 <-> ddr4Port
      fpgaClk --> ddr4.C0_SYS_CLK
      fpgaRst --> ddr4.SYS_RST
      fpgaRst --> clkWiz.RESET

      ddr4.ADDN_UI_CLKOUT(1, ddr4OutDomain) --> clkWiz.CLK_IN1

      clkWiz.LOCKED --> pcr.DCM_LOCKED
      clkWiz.CLK_OUT(1, peripheryDomain) --> pcr.SLOWEST_SYNC_CLK

      val axiInfts = Seq(mem_axi4, mmio_axi4, l2_frontend_bus_axi4).flatten
      axiInfts.foreach { axiInft => AXIMM(axiInft) }

      if (p(HasSDCardPMOD).isDefined) {
        val ports: Seq[SDIOPort] = Seq(SDIOCDPort(), SDIOClkPort(), SDIOCmdPort(), SDIODataPort())
        val sdPmod = SDCardPMOD(pmodIdx = p(HasSDCardPMOD).get)
        ports.foreach { p => sdPmod <-> p}
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
          override def friendlyName: String = "jtag_mfr_id_constant"
        }
        mfrIdConst --> jtagIO.mfr_id

        val partNumConst = new InlineConstant(0.U, jtagIO.part_number.getWidth) {
          override def friendlyName: String = "jtag_part_number_constant"
        }
        partNumConst --> jtagIO.part_number

        val versionConst = new InlineConstant(0.U, jtagIO.version.getWidth) {
          override def friendlyName: String = "jtag_version_constant"
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