package soct.system.vivado

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.InModuleBody
import soct.system.soceteer.SOCTSystem
import soct.{BdBuilderKey, HasDDR4ExtMem, HasSDCardPMOD, PeripheryClockDomain, XilinxFPGAKey, log}
import soct.system.vivado.components.{BSCAN, BSCAN2JTAG, ClkWiz, DDR4, InlineConstant, ProcSysReset, SDCardPMOD, SDIOCDPort, SDIOClkPort, SDIOCmdPort, SDIODataPort, SOCTVivadoSystemTop}
import soct.system.vivado.fpga.FPGARegistry
import soct.system.vivado.abstracts._
import soct.system.vivado.intf.{AXIMM, JTAG}
import soct.system.vivado.signal.{CLOCK, RESET}


/**
 * Top-level module for synthesis of the RocketSystem within SOCT using Vivado
 */
class SOCTVivadoSystem(implicit p: Parameters) extends SOCTSystem {

  if (p(BdBuilderKey).isDefined) {
    implicit val bd: SOCTBdBuilder = p(BdBuilderKey).get
    InModuleBody {
      // Instantiate the FPGA board from class stored in parameters
      val fpga = FPGARegistry.resolveBoardInstance(p(XilinxFPGAKey).get)
      val fpgaDom = fpga.fastestClock()

      val peripheryDomain = ClockDomain(freqMHz = p(PeripheryClockDomain), tclVarName = Some("$periphery_clk_freq"))
      val peripheryReset = WithDomain(peripheryDomain) { implicit dom => ProcSysReset() }
      peripheryDomain.reset = Some(peripheryReset.PeripheralAResetN) // Watch out for active-low reset, change to other polarity if needed

      // Default to 100 MHz - TODO use parameters
      val coreDomain = ClockDomain(100.0, tclVarName = Some("$core_clk_freq"), reset = Some(peripheryReset.PeripheralReset))
      val topInstance = WithDomain(coreDomain) {
        implicit dom =>
          val resetIntf = RESET("SYS_RESET")
          val clockIntf = CLOCK("SYS_CLK")
          new SOCTVivadoSystemTop(this).withRESET(resetIntf).withCLOCK(clockIntf)
      }


      bd.init(p, topInstance, fpga)

      // Connect DDR4 if present - it outputs to the clock wizard
      if (p(HasDDR4ExtMem)) {
        val ddr4Port = fpga.portsDDR4().headOption.getOrElse(
          throw new XilinxDesignException(s"FPGA ${fpga.friendlyName} does not have any DDR4 ports defined but HasDDR4ExtMem is set in parameters.")
        )
        // TODO: Currently uses core frequency for DDR4 clock wizard - can/should we drive it as fast as possible instead?
        val ddr4OutDom = ClockDomain(freqMHz = coreDomain.freqMHz, reset = fpgaDom.reset)
        val ddr4 = WithDomain(fpgaDom) { implicit fpgaDom => DDR4(Seq(ddr4OutDom)) }
        require(ddr4Port.outputToL(ddr4.getPin(ddr4Port)))

        WithDomain(ddr4OutDom) { implicit dom =>
          ClkWiz(Seq(coreDomain, peripheryDomain))
        }
      }

      val axiInfts = Seq(mem_axi4, mmio_axi4, l2_frontend_bus_axi4).flatten
      axiInfts.foreach { axiInft => AXIMM(axiInft) }

      if (p(HasSDCardPMOD).isDefined) {
        val ports = Seq(SDIOCDPort(), SDIOClkPort(), SDIOCmdPort(), SDIODataPort())
        val sdPmod = WithDomain(peripheryDomain) { implicit dom => SDCardPMOD(pmodIdx = p(HasSDCardPMOD).get) }
        ports.foreach { port => port.outputToL(sdPmod.getPin(port)) }
      }

      val debugIf = debug.getWrappedValue.get

      if (debugIf.systemjtag.isDefined) {
        // Create TDT signal for Vivado JTAG integration - TDO is driven when TDT is low
        val jtagIO = debugIf.systemjtag.get
        val jtag = jtagIO.jtag
        val jtag_tdt = IO(Output(Bool())).suggestName("jtag_tdt")
        jtag_tdt := ~jtag.TDO.driven
        val jtagXIntf = JTAG(jtag, jtag_tdt)

        // Tie off unused fields using inline constants - rename for clarity in block design
        val mfrIdConst = new InlineConstant("b10010001001".U, jtagIO.mfr_id.getWidth) {
          override def friendlyName: String = "jtag_mfr_id_constant"
        }
        require(mfrIdConst.outputTo(jtagIO.mfr_id))

        val partNumConst = new InlineConstant(0.U, jtagIO.part_number.getWidth) {
          override def friendlyName: String = "jtag_part_number_constant"
        }
        require(partNumConst.outputTo(jtagIO.part_number))

        val versionConst = new InlineConstant(0.U, jtagIO.version.getWidth) {
          override def friendlyName: String = "jtag_version_constant"
        }
        require(versionConst.outputTo(jtagIO.version))

        val bscan = BSCAN()
        val b2j = BSCAN2JTAG()

        require(bscan.outputToL(b2j.getPin(bscan)))
        require(b2j.outputToL(jtagXIntf.getPin(b2j)))
      }
    }
  }
  else {
    log.info("No BDBuilder found in parameters - skipping block design generation and only elaborating Chisel design. " +
      "If you intended to use Vivado, please ensure HasBdBuilder is set in the parameters.")
  }
}