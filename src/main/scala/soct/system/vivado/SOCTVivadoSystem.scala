package soct.system.vivado

import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.InModuleBody
import soct._
import soct.system.vivado.abstracts._
import soct.system.vivado.components._
import soct.system.vivado.fpga.FPGAClockDomain


/**
 * Top-level module for synthesis of the RocketSystem within SOCT using Vivado, with a single
 * DDR4 memory channel. The shared structure (buses, clock domains, UART, SD card, debug/JTAG)
 * comes from [[SOCTVivadoSystemBase]]; this class adds the single-channel memory topology and
 * its dedicated DDR reset domain.
 */
class SOCTVivadoSystem(implicit p: Parameters) extends SOCTVivadoSystemBase {
  InModuleBody {

    // --------------------------------------------------------------------------
    // Board / Top init (shared) + single-channel checks
    // --------------------------------------------------------------------------
    val c = initCommonDesign()

    val extMems = p(RegisteredMems)
    if (!(c.axiMems.length == 1 && extMems.length == 1)) {
      throw VivadoDesignException("SOCTVivadoSystem requires exactly one DDR4 memory controller defined.")
    }
    val axiMem = c.axiMems.head

    val FPGAClockDomain(fpgaClk, fpgaRst, _) = c.fpga.initNClockPorts(1).head

    // --------------------------------------------------------------------------
    // Memory path (single channel) + DDR reset domain
    // --------------------------------------------------------------------------
    val ddr4Param = extMems.head
    val ddr4Info: DDR4Info = DDR4Info(ddr4Param, ddr4Param.initPort, axiMem)

    val ddrPsr = ProcSysReset().withInstanceName("ddr_psr")
    val memPath = MemPath(DDR4(ddr4Info), AXISmartConnect().withInstanceName("mem_smc"))
    val ddr4 = memPath.ddr4Inst

    // --------------------------------------------------------------------------
    // Derived pins (clock outputs / DDR helper pins)
    // --------------------------------------------------------------------------
    val peripheryClock = ddr4.ADDN_UI_CLKOUT(1, c.peripheryDomain)
    val coreClock = ddr4.ADDN_UI_CLKOUT(2, c.coreDomain)

    // --------------------------------------------------------------------------
    // Timing constraints
    // --------------------------------------------------------------------------
    val (coreClockObj, corePeriodProp) = registerCoreClockCapture(coreClock.ref)
    addDdr4TimingConstraints(ddr4, coreClockObj, corePeriodProp)

    // --------------------------------------------------------------------------
    // Fundamental interconnect (interfaces & major clocks)
    // --------------------------------------------------------------------------
    fpgaClk --> ddr4.C0_SYS_CLK
    fpgaRst --> ddr4.SYS_RST
    ddr4.C0_DDR4_UI_CLK --> ddrPsr.SLOWEST_SYNC_CLK

    // --------------------------------------------------------------------------
    // Reset strategy
    // --------------------------------------------------------------------------
    fpgaRst --> ddrPsr.EXT_RESET_IN
    wireDebugReset(fpgaRst, c)

    // DDR4 doesn't expose an explicit MMCM-locked pin, but `c0_init_calib_complete`
    // is a superset: it asserts only after the MMCM has locked AND the DRAM init
    // calibration is finished. Using it as DCM_LOCKED conservatively holds the
    // periph and core resets until DDR4 is fully ready — which is what we want
    // anyway, since nothing useful can run before DRAM is up.
    ddr4.C0_INIT_CALIB_COMPLETE --> Seq(c.periphPsr.DCM_LOCKED, c.corePsr.DCM_LOCKED, ddrPsr.DCM_LOCKED)

    // Domain clocks and periphery/core fabric (shared)
    wirePeripheryFabric(peripheryClock, c)
    wireCoreFabric(coreClock, c)

    // Memory SmartConnect: core clock on the slave side, DDR UI clock on the master side
    coreClock --> memPath.memSMC.ACLK(0)
    ddr4.C0_DDR4_UI_CLK --> memPath.memSMC.ACLK(1)

    // DDR reset domain distribution
    ddrPsr.PeripheralAResetN --> ddr4.C0_DDR4_ARESETN

    // memSMC reset is influenced by BOTH core and DDR domains:
    // hold in reset if either domain is in reset, release only when BOTH are out of reset.
    AND(c.corePsr.PeripheralAResetN, ddrPsr.PeripheralAResetN).withInstanceName("memSMC_reset") --> memPath.memSMC.ARESETN

    // --------------------------------------------------------------------------
    // Interrupts, AXI paths, SD card, debug (shared)
    // --------------------------------------------------------------------------
    wireInterrupts(c)

    // Memory path: Rocket mem AXI -> memSMC (CDC + width conversion) -> DDR4
    memPath.memSMC.S_AXI(0) <-> axiMem.bdPin
    memPath.memSMC.M_AXI(0) <-> ddr4.C0_DDR4_S_AXI

    wireMmioAndDma(c)
    wireSdCardPmod(peripheryClock, c)
    wireDebugAndJtag(coreClock, coreClockObj, corePeriodProp, c)
    tieOffHartResets()
  }
}
