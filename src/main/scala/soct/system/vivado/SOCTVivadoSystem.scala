package soct.system.vivado

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.InModuleBody
import soct.system.soceteer.SOCTSystem
import soct.system.vivado.SOCTVivado.toXilinxPortRef
import soct.{BdBuilderKey, HasDDR4ExtMem, HasSDCardPMOD, HasSOCTConfig, PeripheryClockDomain, XilinxFPGAKey, log}
import soct.system.vivado.components.{AXIXIntfPort, AutoConnect, BSCAN, BSCAN2JTAG, ClkWiz, ClockDomain, DDR4, InlineConstant, InstantiableBdComp, IsModule, JTAGXIntfPort, ProcSysReset, SDCardPMOD, SDIOCDPort, SDIOClkPort, SDIOCmdPort, SDIODataPort, WithDomain}
import soct.system.vivado.fpga.FPGARegistry


/**
 * Top-level module for synthesis of the RocketSystem within SOCT using Vivado
 */
class SOCTVivadoSystem(implicit p: Parameters) extends SOCTSystem {

  // TODO this only works for a single clock domain for now - we should enable multiple clock domains for different buses
  private def genTopInst()(implicit p: Parameters, bd: SOCTBdBuilder, dom: Option[ClockDomain]): InstantiableBdComp with IsModule = {
    // This is the top-level instance representing this system in the block design
    new InstantiableBdComp with IsModule with AutoConnect {
      private val c = p(HasSOCTConfig)

      // Returns either port refs for clocks or resets depending on the parameter
      def ioClockOrReset(isReset: Boolean): Seq[String] = {
        val busClockOrResetPorts = io_clocks
          .map(_.getWrappedValue)
          .map(_.data) // Option[Iterable[ClockBundle]]
          .toSeq // Seq[Iterable[ClockBundle]] (0 or 1 element)
          .flatten // Seq[ClockBundle]
          .map { bundle =>
            if (isReset) toXilinxPortRef(bundle.reset) else toXilinxPortRef(bundle.clock)
          }

        val debugClockOrResetPort = if (isReset) {
          toXilinxPortRef(debug.getWrappedValue.get.reset)
        } else {
          toXilinxPortRef(debug.getWrappedValue.get.clock)
        }

        busClockOrResetPorts :+ debugClockOrResetPort
      }

      override def resetInPorts: Seq[String] = {
        ioClockOrReset(isReset = true) // Active-high resets
      }

      override def clockInPorts: Seq[String] = {
        ioClockOrReset(isReset = false)
      }

      override def reference: String = c.topModuleName

      override def friendlyName: String = SOCTVivadoSystem.this.instanceName

      override def instanceName: String = friendlyName

      override def connectTclCommands: Seq[String] = Seq.empty // Top module is only receiver of connections
    }
  }


  if (p(BdBuilderKey).isDefined) {
    implicit val bd: SOCTBdBuilder = p(BdBuilderKey).get
    // Instantiate the FPGA board from class stored in parameters
    val fpga = FPGARegistry.resolveBoardInstance(p(XilinxFPGAKey).get)
    val fpgaDomain = fpga.fastestClock()

    val peripheryDomain = ClockDomain(freqMHz = p(PeripheryClockDomain), tclVarName = Some("$periphery_clk_freq"))
    val peripheryReset = WithDomain(peripheryDomain) { implicit dom => ProcSysReset() }
    peripheryDomain.reset = Some(peripheryReset.PeripheralAResetN) // Watch out for active-low reset, change to other polarity if needed

    // Default to 100 MHz - TODO use parameters
    val coreDomain = ClockDomain(100.0, tclVarName = Some("$core_clk_freq"), reset=Some(peripheryReset.PeripheralReset))
    val topInstance = WithDomain(coreDomain) { implicit dom => genTopInst() }

    bd.init(p, topInstance, fpga)

    InModuleBody {
      // Connect DDR4 if present - it outputs to the clock wizard
      if (p(HasDDR4ExtMem)) {
        val ddr4Port = fpga.portsDDR4().headOption.getOrElse(
          throw new XilinxDesignException(s"FPGA ${fpga.friendlyName} does not have any DDR4 ports defined but HasDDR4ExtMem is set in parameters.")
        )
        // TODO: Currently uses core frequency for DDR4 clock wizard - can/should we drive it as fast as possible instead?
        val ddr4OutDom = ClockDomain(freqMHz = coreDomain.freqMHz, reset = fpgaDomain.reset)
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
  }
  else {
    log.info("No BDBuilder found in parameters - skipping block design generation and only elaborating Chisel design. " +
      "If you intended to use Vivado, please ensure HasBdBuilder is set in the parameters.")
  }
}