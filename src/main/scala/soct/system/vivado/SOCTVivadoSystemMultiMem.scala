package soct.system.vivado

import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.InModuleBody
import soct._
import soct.system.vivado.abstracts._
import soct.system.vivado.components._
import soct.system.vivado.fpga.FPGAClockDomain


/**
 * Top-level module for synthesis of the RocketSystem within SOCT using Vivado, with one DDR4
 * controller per memory channel. The shared structure (buses, clock domains, UART, SD card,
 * debug/JTAG) comes from [[SOCTVivadoSystemBase]]; this class adds the multi-channel memory
 * topology: one board clock per channel, per-channel address deinterleavers, and the combined
 * calibration gate.
 */
class SOCTVivadoSystemMultiMem(implicit p: Parameters) extends SOCTVivadoSystemBase with SupportsMultiMem {
  InModuleBody {

    // --------------------------------------------------------------------------
    // Board / Top init (shared) + per-channel clock ports
    // --------------------------------------------------------------------------
    val c = initCommonDesign()

    val mems = p(RegisteredMems)
    val fpgaDoms: Seq[FPGAClockDomain] = try {
      c.fpga.initNClockPorts(mems.size)
    } catch {
      case ex: VivadoDesignException =>
        soct.log.error("SOCTVivadoSystemMultiMem requires one clock port per memory channel")
        throw ex
    }
    val fpgaRst = fpgaDoms.head.reset

    // --------------------------------------------------------------------------
    // Memory paths (one per channel, with address deinterleavers)
    // --------------------------------------------------------------------------
    if (c.axiMems.length != mems.length) {
      throw VivadoDesignException("The number of AXI4 memory interfaces of the RocketSystem does not match the number of DDR4 memory layouts specified in RegisteredMems.")
    }

    // Multi-channel memory ports are cache-line interleaved by RocketChip: each channel only sees
    // addresses whose channel-select bits match its index. A deinterleaver per channel compacts
    // that sparse view onto a dense range at the memory base before it reaches the DDR4 controller.
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
    val mainMem: DDR4 = memPaths.head.ddr4Inst

    // --------------------------------------------------------------------------
    // Derived pins (clock outputs / DDR helper pins)
    // --------------------------------------------------------------------------
    val coreClock = mainMem.ADDN_UI_CLKOUT(1, c.coreDomain)
    val peripheryClock = mainMem.ADDN_UI_CLKOUT(2, c.peripheryDomain)

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

    // Combine every channel's calibration-complete into one gate: the core and periphery only
    // leave reset when ALL DDR4 controllers are calibrated (see the single-channel system for
    // why calib-complete stands in for an MMCM-locked signal).
    val calibSignals = memPaths.map(_.ddr4Inst.C0_INIT_CALIB_COMPLETE.asInstanceOf[BdPinOut])
    val ddr4InitComplete: BdPinOut = calibSignals.zipWithIndex.tail.foldLeft(calibSignals.head) {
      case (acc, (next, j)) =>
        AND(acc, next).withInstanceName(s"ddr4_calib_complete_gate_$j").RES
    }

    ddr4InitComplete --> Seq(c.periphPsr.DCM_LOCKED, c.corePsr.DCM_LOCKED)

    // Domain clocks and periphery/core fabric (shared)
    wirePeripheryFabric(peripheryClock, c)
    wireCoreFabric(coreClock, c)

    // Memory SmartConnects: core clock on the slave side, per-channel DDR UI clock on the master side
    coreClock --> memPaths.map(_.memSMC.ACLK(0))
    // Deinterleavers are combinational; the clock only associates their AXI interfaces with the core domain
    coreClock --> memPaths.flatMap(_.ddr4Inst.info.deinterleaver).map(_.ACLK)

    memPaths.foreach { path =>
      path.ddr4Inst.C0_DDR4_UI_CLK --> path.memSMC.ACLK(1) // 0 is connected to coreClock
    }

    // Memory reset distribution (all channels in the core reset domain)
    c.corePsr.PeripheralAResetN --> memPaths.map(_.ddr4Inst.C0_DDR4_ARESETN)
    c.corePsr.PeripheralAResetN --> memPaths.map(_.memSMC.ARESETN)

    // --------------------------------------------------------------------------
    // Interrupts, AXI paths, SD card, debug (shared)
    // --------------------------------------------------------------------------
    wireInterrupts(c)

    // Memory path per channel: Rocket mem AXI -> (deinterleaver) -> memSMC (CDC + width conversion) -> DDR4
    memPaths.foreach { path =>
      path.memSMC.M_AXI(0) <-> path.ddr4Inst.C0_DDR4_S_AXI
      path.ddr4Inst.info.deinterleaver match {
        case Some(deint) =>
          deint.S_AXI <-> path.ddr4Inst.info.mAxi.bdPin
          path.memSMC.S_AXI(0) <-> deint.M_AXI
        case None =>
          path.memSMC.S_AXI(0) <-> path.ddr4Inst.info.mAxi.bdPin
      }
    }

    wireMmioAndDma(c)
    wireSdCardPmod(peripheryClock, c)
    wireVideoStream(coreClock, peripheryClock, c)
    wireDebugAndJtag(coreClock, coreClockObj, corePeriodProp, c)
    tieOffHartResets()
  }
}
