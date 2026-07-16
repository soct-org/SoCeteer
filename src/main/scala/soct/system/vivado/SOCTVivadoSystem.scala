package soct.system.vivado

import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.InModuleBody
import soct._
import soct.SOCTFreq._
import soct.system.vivado.abstracts._
import soct.system.vivado.components._
import soct.system.vivado.fpga.FPGAClockDomain


/**
 * Top-level module for synthesis of the RocketSystem within SOCT using Vivado, with one DDR4
 * controller per memory channel (one channel being the common case). The shared structure
 * (buses, clock domains, UART, SD card, debug/JTAG) comes from [[SOCTVivadoSystemBase]]; this
 * class adds the memory topology - one board clock and a dedicated DDR reset domain per
 * channel, address deinterleavers when the channels are cache-line interleaved - and the
 * system clock synthesis (see the companion's `SysClkWizTapFreq` documentation).
 */
class SOCTVivadoSystem(implicit p: Parameters) extends SOCTVivadoSystemBase with SupportsMultiMem {
  InModuleBody {

    // --------------------------------------------------------------------------
    // Board / Top init (shared) + per-channel clock ports
    // --------------------------------------------------------------------------
    val c = initCommonDesign()

    val mems = p(RegisteredMems)
    if (mems.isEmpty) {
      throw VivadoDesignException("SOCTVivadoSystem requires at least one DDR4 memory controller defined.")
    }
    if (c.axiMems.length != mems.length) {
      throw VivadoDesignException("The number of AXI4 memory interfaces of the RocketSystem does not match the number of DDR4 memory layouts specified in RegisteredMems.")
    }

    val fpgaDoms: Seq[FPGAClockDomain] = try {
      c.fpga.initNClockPorts(mems.size)
    } catch {
      case ex: VivadoDesignException =>
        soct.log.error("SOCTVivadoSystem requires one clock port per memory channel")
        throw ex
    }
    val fpgaRst = fpgaDoms.head.reset

    // --------------------------------------------------------------------------
    // Memory paths (one per channel, with address deinterleavers when interleaved)
    // --------------------------------------------------------------------------

    // Multi-channel memory ports are cache-line interleaved by RocketChip: each channel only sees
    // addresses whose channel-select bits match its index. A deinterleaver per channel compacts
    // that sparse view onto a dense range at the memory base before it reaches the DDR4
    // controller. Contiguous (single-channel) designs get no deinterleaver.
    val ddr4Params: Seq[DDR4Info] = mems.zipWithIndex.map {
      case (param, i) =>
        val mem = c.axiMems(i)
        val deintOpt = AXIAddrDeinterleaver.fromBusInfo(mem).map(_.withInstanceName(s"mem_deint_$i"))
        DDR4Info(param, param.initPort, mem, deintOpt)
    }

    // Sanity-check the interleave geometry across channels: all channels must agree on block size,
    // channel count and base, and together they must cover every channel index exactly once.
    locally {
      val geos = ddr4Params.flatMap(_.deinterleaver).map(_.geometry)
      if (geos.nonEmpty) {
        if (geos.size != mems.size)
          throw VivadoDesignException(s"Only ${geos.size} of ${mems.size} memory channels are interleaved. Mixed interleaved/contiguous channels are not supported.")
        if (geos.map(g => (g.dropLsb, g.dropBits, g.base)).distinct.size != 1)
          throw VivadoDesignException(s"Memory channels disagree on interleave geometry: ${geos.mkString(", ")}")
        if (geos.head.nChannels != mems.size || geos.map(_.channelIndex).distinct.size != mems.size)
          throw VivadoDesignException(s"Interleave geometry does not match the number of memory channels (${mems.size}): ${geos.mkString(", ")}")
      }
    }

    val memPaths = ddr4Params.zipWithIndex.map { case (info, i) =>
      val smc = AXISmartConnect().withInstanceName(s"mem_smc_$i")
      MemPath(DDR4(info), smc)
    }

    // --------------------------------------------------------------------------
    // System clock synthesis (core + periphery domains)
    // --------------------------------------------------------------------------
    val sysClkWiz = ClkWiz(inputFreq = Some(SOCTVivadoSystem.SysClkWizTapFreq)).withInstanceName("sys_clk_wiz")
    memPaths.head.ddr4Inst.ADDN_UI_CLKOUT(1, new ClockDomain(SOCTVivadoSystem.SysClkWizTapFreq)) --> sysClkWiz.CLK_IN.next()
    fpgaRst --> sysClkWiz.RESET
    val coreClock = sysClkWiz.CLK_OUT(1, c.coreDomain)
    val peripheryClock = sysClkWiz.CLK_OUT(2, c.peripheryDomain)

    // --------------------------------------------------------------------------
    // Timing constraints
    // --------------------------------------------------------------------------
    val (coreClockObj, corePeriodProp) = registerCoreClockCapture(coreClock.ref)
    memPaths.map(_.ddr4Inst).foreach(addDdr4TimingConstraints(_, coreClockObj, corePeriodProp))

    // --------------------------------------------------------------------------
    // Fundamental interconnect (interfaces & major clocks)
    // --------------------------------------------------------------------------
    memPaths.map(_.ddr4Inst).zip(fpgaDoms) foreach { case (ddr4, dom) =>
      dom.clock --> ddr4.C0_SYS_CLK
      dom.reset --> ddr4.SYS_RST
    }

    // --------------------------------------------------------------------------
    // Reset strategy
    // --------------------------------------------------------------------------
    wireDebugReset(fpgaRst, c)

    // Per-channel DDR reset domain, synchronized to that channel's UI clock. The DDR4
    // doesn't expose an explicit MMCM-locked pin, but `c0_init_calib_complete` is a
    // superset: it asserts only after the MMCM has locked AND the DRAM init calibration
    // is finished.
    val ddrPsrs = memPaths.zip(fpgaDoms).zipWithIndex.map { case ((path, dom), i) =>
      val psr = ProcSysReset().withInstanceName(s"ddr_psr_$i")
      path.ddr4Inst.C0_DDR4_UI_CLK --> psr.SLOWEST_SYNC_CLK
      dom.reset --> psr.EXT_RESET_IN
      path.ddr4Inst.C0_INIT_CALIB_COMPLETE --> psr.DCM_LOCKED
      psr.PeripheralAResetN --> path.ddr4Inst.C0_DDR4_ARESETN
      psr
    }

    // Core and periphery leave reset only when ALL DDR4 controllers are calibrated (nothing
    // useful can run before DRAM is up) AND the system clock wizard has locked.
    val calibSignals = memPaths.map(_.ddr4Inst.C0_INIT_CALIB_COMPLETE.asInstanceOf[BdPinOut])
    val ddr4InitComplete: BdPinOut = calibSignals.zipWithIndex.tail.foldLeft(calibSignals.head) {
      case (acc, (next, j)) =>
        AND(acc, next).withInstanceName(s"ddr4_calib_complete_gate_$j").RES
    }
    AND(ddr4InitComplete, sysClkWiz.LOCKED).withInstanceName("clocks_ready") --> Seq(c.periphPsr.DCM_LOCKED, c.corePsr.DCM_LOCKED)

    // Domain clocks and periphery/core fabric (shared)
    wirePeripheryFabric(peripheryClock, c)
    wireCoreFabric(coreClock, c)

    // Memory SmartConnects: core clock on the slave side, per-channel DDR UI clock on the master side
    coreClock --> memPaths.map(_.memSMC.ACLK.next())
    // Deinterleavers are combinational; the clock only associates their AXI interfaces with the core domain
    coreClock --> memPaths.flatMap(_.ddr4Inst.info.deinterleaver).map(_.ACLK)

    memPaths.foreach { path =>
      path.ddr4Inst.C0_DDR4_UI_CLK --> path.memSMC.ACLK.next() // after the coreClock aclk
    }

    // memSMC reset is influenced by BOTH the core and its channel's DDR domain:
    // hold in reset if either domain is in reset, release only when BOTH are out of reset.
    memPaths.zip(ddrPsrs).zipWithIndex.foreach { case ((path, ddrPsr), i) =>
      AND(c.corePsr.PeripheralAResetN, ddrPsr.PeripheralAResetN).withInstanceName(s"mem_smc_reset_$i") --> path.memSMC.ARESETN
    }

    // --------------------------------------------------------------------------
    // Interrupts, AXI paths, SD card, video, debug (shared)
    // --------------------------------------------------------------------------
    wireInterrupts(c)

    // Memory path per channel: Rocket mem AXI -> (deinterleaver) -> memSMC (CDC + width conversion) -> DDR4
    memPaths.foreach { path =>
      path.memSMC.M_AXI.next() <-> path.ddr4Inst.C0_DDR4_S_AXI
      path.ddr4Inst.info.deinterleaver match {
        case Some(deint) =>
          deint.S_AXI <-> path.ddr4Inst.info.mAxi.bdPin
          path.memSMC.S_AXI.next() <-> deint.M_AXI
        case None =>
          path.memSMC.S_AXI.next() <-> path.ddr4Inst.info.mAxi.bdPin
      }
    }

    wireMmioAndDma(c)
    wireSdCardPmod(peripheryClock, c)
    wireVideoStream(coreClock, peripheryClock, c)
    wireDebugAndJtag(coreClock, coreClockObj, corePeriodProp, c)
    tieOffHartResets()
  }
}

object SOCTVivadoSystem {
  /**
   * Nominal frequency of the DDR4 additional clock output that feeds the system clock wizard.
   *
   * The core and periphery clocks are synthesized by a clocking wizard instead of being taken
   * from DDR4 additional clock outputs directly, so the frequencies requested via
   * `--core-freq-mhz`/`--periphery-freq-mhz` are not limited to what the DDR4 controller's
   * MMCM (whose VCO is dictated by the memory part) happens to offer. Only this one fixed tap
   * must be achievable by the DDR4 MMCM; Vivado validates that when it applies
   * `ADDN_UI_CLKOUT1_FREQ_HZ`.
   *
   * The tap is deliberately an exact integer frequency: Vivado propagates the wizard's ACTUAL
   * output frequencies as `FREQ_HZ` along the design, and the top-level interface annotations
   * carry the configured nominal values - a fractional input (e.g. the DDR4 UI clock) would
   * make the achieved outputs miss the nominals and fail validation on the mismatch.
   */
  private val SysClkWizTapFreq: Freq = 100.MHz
}
