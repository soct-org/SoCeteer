package soct.system.vivado

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.InModuleBody
import soct.system.soceteer.SOCTSystem
import soct.system.vivado.SOCTVivado.toXilinxPortRef
import soct.{HasBdBuilder, HasDDR4ExtMem, HasSDCardPMOD, HasSOCTConfig, HasXilinxFPGA, PeripheryClockDomain, log}
import soct.system.vivado.components.{AXIXIntfPort, BSCAN, BSCAN2JTAG, ClkWiz, ClockDomain, DDR4, InlineConstant, InstantiableBdComp, IsModule, JTAGXIntfPort, SDCardPMOD, SDIOCDPort, SDIOClkPort, SDIOCmdPort, SDIODataPort, WithDomain}


/**
 * Top-level module for synthesis of the RocketSystem within SOCT using Vivado
 */
class SOCTVivadoSystem(implicit p: Parameters) extends SOCTSystem {
  // TODO this only works for a single clock domain for now - we should enable multiple clock domains for different buses
  private def genTopInst(coreDomain: ClockDomain)(implicit p: Parameters, bd: SOCTBdBuilder):
  InstantiableBdComp with IsModule = {
    // This is the top-level instance representing this system in the block design
    WithDomain(coreDomain) { implicit dom =>
      new InstantiableBdComp with IsModule {
        private val c = p(HasSOCTConfig)

        override def clockInPorts: Seq[String] = {
          val busClockPorts = io_clocks
            .map(_.getWrappedValue)
            .map(_.data) // Option[Iterable[ClockBundle]]
            .toSeq // Seq[Iterable[ClockBundle]] (0 or 1 element)
            .flatten // Seq[ClockBundle]
            .map(bundle => toXilinxPortRef(bundle.clock))
          val debugClockPorts = toXilinxPortRef(debug.getWrappedValue.get.clock)

          busClockPorts :+ debugClockPorts
        }

        override def reference: String = c.topModuleName

        override def friendlyName: String = SOCTVivadoSystem.this.instanceName

        override def instanceName: String = friendlyName

        override def connectTclCommands: Seq[String] = Seq.empty // Top module is only receiver of connections
      }
    }
  }


  if (p(HasBdBuilder).isDefined) {
    implicit val bd: SOCTBdBuilder = p(HasBdBuilder).get
    val fpga = p(HasXilinxFPGA).get
    val fpgaDomain = fpga.fastestClock()
    val coreDomain = ClockDomain("core", 100.0, tclVarName = Some("$core_clk_freq")) // Default to 100 MHz - TODO use parameters
    val peripheryDomain = ClockDomain("periphery", freqMHz=p(PeripheryClockDomain), tclVarName = Some("$periphery_clk_freq"))
    val topInstance = genTopInst(coreDomain)

    bd.init(p, topInstance) // Register this top instance with the BDBuilder.

    InModuleBody {
      // Connect DDR4 if present - it outputs to the clock wizard
      if (p(HasDDR4ExtMem)) {
        val ddr4Port = fpga.portsDDR4().headOption.getOrElse(
          throw new XilinxDesignException()(s"FPGA ${fpga.friendlyName} does not have any DDR4 ports defined but HasDDR4ExtMem is set in parameters.")
        )
        // TODO: Currently uses core frequency for DDR4 clock wizard - can/should we drive it as fast as possible instead?
        val ddr4OutDom = fpgaDomain.copy(name="ddr4", freqMHz=coreDomain.freqMHz)
        WithDomain(fpgaDomain) { implicit fpgaDom =>
          DDR4(ddr4Intf = ddr4Port, addnClkOut1 = Some(ddr4OutDom))
        }
        WithDomain(ddr4OutDom) { implicit dom =>
          ClkWiz(Seq(coreDomain, peripheryDomain))
        }
      }

      val axiInfts = Seq(mem_axi4, mmio_axi4, l2_frontend_bus_axi4).flatten
      axiInfts.foreach { axiInft =>
        AXIXIntfPort(axiInft)
      }


      if (p(HasSDCardPMOD).isDefined) {
        WithDomain(peripheryDomain) { implicit dom =>
          SDCardPMOD(
            pmodIdx = p(HasSDCardPMOD).get,
            cdPort = SDIOCDPort(),
            clkPort = SDIOClkPort(),
            cmdPort = SDIOCmdPort(),
            dataPort = SDIODataPort()
          )
        }
      }

      val debugIf = debug.getWrappedValue.get

      if (debugIf.systemjtag.isDefined) {
        // Create TDT signal for Vivado JTAG integration - TDO is driven when TDT is low
        val jtagIO = debugIf.systemjtag.get
        val jtag_tdt = IO(Output(Bool())).suggestName("jtag_tdt")
        jtag_tdt := ~jtagIO.jtag.TDO.driven
        val jtagXIntf = JTAGXIntfPort(jtagIO.jtag, jtag_tdt)

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

        require(bscan.outputTo(b2j))
        require(b2j.outputTo(jtagXIntf))
      }
    }
  } else {
    log.info("No BDBuilder found in parameters - skipping block design generation and only elaborating Chisel design. " +
      "If you intended to use Vivado, please ensure HasBdBuilder is set in the parameters.")
  }
}