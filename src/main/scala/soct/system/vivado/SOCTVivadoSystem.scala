package soct.system.vivado

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.InModuleBody
import soct.system.soceteer.SOCTSystem
import soct.{HasBdBuilder, HasDDR4ExtMem, HasSDCardPMOD, HasSOCTConfig, HasXilinxFPGA, PeripheryClockFrequency, log}
import soct.system.vivado.components.{AXIXIntfPort, BSCAN, BSCAN2JTAG, ClkWiz, ClockDomain, DDR4, InlineConstant, InstantiableBdComp, IsModule, JTAGXIntfPort, SDCardPMOD, SDIOCDPort, SDIOClkPort, SDIOCmdPort, SDIODataPort, WithDomain}

/**
 * Top-level module for synthesis of the RocketSystem within SOCT using Vivado
 */
class SOCTVivadoSystem(implicit p: Parameters) extends SOCTSystem {
  if (p(HasBdBuilder).isDefined) {
    implicit val bd: SOCTBdBuilder = p(HasBdBuilder).get
    val fpga = p(HasXilinxFPGA).get
    val fpgaDomain = fpga.clocks.headOption.getOrElse(
      throw XilinxDesignException(s"FPGA ${fpga.friendlyName} does not define any clock domains.")
    )
    val coreDomain = ClockDomain("core", 100.0)
    val peripheryDomain = ClockDomain("periphery", p(PeripheryClockFrequency))
    val cklWiz = ClkWiz(Seq(coreDomain, peripheryDomain))

    // This is the top-level instance representing this system in the block design
    val topInstance = WithDomain(coreDomain) { implicit dom =>
        new InstantiableBdComp with IsModule {
          private val c = p(HasSOCTConfig)

          override def reference: String = c.topModuleName

          override def friendlyName: String = SOCTVivadoSystem.this.instanceName

          override def instanceName: String = friendlyName

          override def connectTclCommands: Seq[String] = Seq.empty // Top module is only receiver of connections
        }
      }
    bd.init(p, topInstance) // Register this top instance with the BDBuilder.


    InModuleBody {
      // Connect DDR4 if present - it outputs to the clock wizard
      if (p(HasDDR4ExtMem).isDefined) {
        val portIdx = p(HasDDR4ExtMem).get
        // get index or throw error if out of range
        require(portIdx >= 0 && portIdx < fpga.portsDDR4.length, s"DDR4 port index $portIdx out of range for FPGA ${fpga.friendlyName} which has ${fpga.portsDDR4.length} DDR4 ports.")
        val ddr4Port = fpga.portsDDR4(portIdx)
        val ddr4 = {
          WithDomain(fpgaDomain) { implicit dom =>
            DDR4(ddr4Intf = ddr4Port)
          }
        }
        ddr4.outputTo(cklWiz)
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

      if (debug.getWrappedValue.isDefined) {
        val debugIf = debug.getWrappedValue.get

        // JTAG interface:
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
  } else {
    log.info("No BDBuilder found in parameters - skipping block design generation and only elaborating Chisel design. " +
      "If you intended to use Vivado, please ensure HasBdBuilder is set in the parameters.")
  }
}