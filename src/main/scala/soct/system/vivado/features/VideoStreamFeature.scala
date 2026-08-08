package soct.system.vivado.features

import freechips.rocketchip.resources.{Description, Device, FixedClockResource, Resource, ResourceAddress, ResourceBinding, ResourceBindings, ResourceInt, ResourceString, SimpleDevice}
import freechips.rocketchip.subsystem.InclusiveCacheKey
import org.chipsalliance.cde.config.Parameters
import soct.SOCTFreq._
import soct._
import soct.vivado._
import soct.system.vivado._
import soct.vivado.abstracts.BdPinPort.portToBdPin
import soct.vivado.abstracts.ClockDomain
import soct.vivado.components._
import soct.vivado.fpga.HasZynqUltraPS
import soct.vivado.misc.{AddressSets, AxiSlaveBinder, DTSInfo, Irq, MmcmSolver, VideoTiming}

/**
 * The DisplayPort video pipeline ([[HasVideoStream]]): VDMA (frames from DRAM) ->
 * AXI4-Stream video out (+ timing controller) -> the PS DP controller's live video
 * input, plus the console framebuffer carve-out and its kernel-facing description.
 * The PS `S_AXI_LPD` port is reachable through the PS window ([[PsWindowFeature]]),
 * so the RISC-V can program the DP controller.
 *
 * The VDMA node follows the mainline `xlnx,axi-dma.yaml` binding exactly, so the stock
 * dmaengine driver (CONFIG_XILINX_DMA) probes it: the controller node carries
 * `#dma-cells`, addressing, `dma-ranges`, `xlnx,num-fstores` and a clock reference
 * (`s_axi_lite_aclk` is the one clock the driver refuses to probe without - emitted
 * here as a fixed-clock, since the periphery clock is fixed in hardware); the interrupt
 * and `xlnx,datawidth` sit on a `dma-channel` CHILD node per the binding.
 *
 * The coherent/incoherent frame-fetch variants ([[VideoStreamParams.incoherent]])
 * differ in exactly three wired places (the VDMA's fabric target, its memory-side
 * clock, its address assignment) and two device-tree places (the `soct,incoherent`
 * marker, the coherent-only `/chosen` framebuffer node) - all in this one class.
 */
class VideoStreamFeature(vs: VideoStreamParams, mmioBus: Device, intcDev: Device,
                         irqs: IrqAllocator, chosenDev: Device, reservedMemoryDev: Device)
                        (implicit p: Parameters, bd: SOCTBdBuilder) extends VivadoFeature {
  override def name: String = "video"

  private val periphHz = p(PeripheryClockDomain).freq.toHz.toLong
  private val vdmaDts = DTSInfo(
    parent = mmioBus,
    regs = Seq(("reg", VivadoMmioMap.VdmaBase, VivadoMmioMap.RegionSize)),
    compatibles = Seq("xlnx,axi-vdma-1.00.a"),
    extraProps = Map(
      "#dma-cells" -> Seq(ResourceInt(1)),
      "#address-cells" -> Seq(ResourceInt(1)),
      "#size-cells" -> Seq(ResourceInt(1)),
      "xlnx,addrwidth" -> Seq(ResourceInt(32)),
      "xlnx,num-fstores" -> Seq(ResourceInt(AXIVideoDMA.FrameStores)),
      // Mirrors the IP's C_FLUSH_ON_FSYNC (a Vivado-resolved parameter, readback = 1 =
      // flush both channels on frame sync). The driver warns without it and would skip
      // the flush handling the hardware actually performs.
      "xlnx,flush-fsync" -> Seq(ResourceInt(1)),
      // The MM2S master reaches DRAM's first (32-bit-addressable) 2 GiB at identical
      // addresses - see AXIVideoDMA.dmaMasterRange for why framebuffers live there.
      "dma-ranges" -> Seq(ResourceInt(0x80000000L), ResourceInt(0x80000000L),
        ResourceInt(0x80000000L)),
      "clock-names" -> Seq(ResourceString("s_axi_lite_aclk"))
    ) ++ Map(
      // Disabled: the engine belongs to the boot-stage display bring-up, which leaves it
      // scanning the console framebuffer - and the dmaengine driver RESETS every channel at
      // probe (xilinx_dma_chan_probe), which would halt that scanout mid-frame. The node
      // stays fully described so flipping this to "okay" is all a future memory-to-memory
      // use needs - at the price of the display.
      "status" -> Seq(ResourceString("disabled"))
    ) ++ (if (!vs.incoherent) Map.empty else Map(
      // The frame master reaches DRAM through its own memory-controller port, bypassing the
      // coherent fabric (soct.WithIncoherentVideoStream): DRAM is NOT coherent with the CPU
      // caches for these frames, so software must make rendered pixels visible before the
      // DMA reads them (L2 Flush64 where an L2 exists, an L1 eviction otherwise). Marked so
      // a driver can select that contract instead of assuming coherent DMA.
      "soct,incoherent" -> Nil
    ))
  )
  private val vdmaDev = AxiSlaveBinder.bindSimpleDevice(devname = "dma-controller", dts = vdmaDts,
    perms = AxiSlaveBinder.mmioPerms)
  new FixedClockResource("periph_clock", periphHz / 1e6).bind(vdmaDev)

  private val vdmaIrq = Irq(intcDev, irqs.claim("vdma", edge = false))
  private val vdmaChanDev = new SimpleDevice("dma-channel", Seq("xlnx,axi-vdma-mm2s-channel")) {
    override def parent: Some[Device] = Some(vdmaDev)
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, AxiSlaveBinder.withXilinxIntcCells(mapping) ++ Map(
        "xlnx,datawidth" -> Seq(ResourceInt(AXIVideoDMA.MmDataWidth))))
    }
  }
  ResourceBinding {
    Resource(vdmaChanDev, "int").bind(intcDev, ResourceInt(vdmaIrq.index))
  }

  // The configured mode is baked into the design (pixel clock); its COMPLETE timing is
  // advertised here (kernel display-timings naming) so drivers program the timing
  // generator and the DisplayPort main stream attributes from the device tree instead
  // of carrying timing tables. The soct,* trio stays as the mode's compact identity.
  private val staticTiming = VideoStreamFeature.timingFor(vs)
  private val vtcDts = DTSInfo(
    parent = mmioBus,
    regs = Seq(("reg", VivadoMmioMap.VtcBase, VivadoMmioMap.RegionSize)),
    compatibles = Seq("xlnx,v-tc-6.2"),
    extraProps = Map(
      "soct,hactive" -> Seq(ResourceInt(vs.width)),
      "soct,vactive" -> Seq(ResourceInt(vs.height)),
      "soct,fps" -> Seq(ResourceInt(vs.fps)),
      "clock-frequency" -> Seq(ResourceInt(BigInt(staticTiming.pixelClock.toHz.toLong))),
      "hfront-porch" -> Seq(ResourceInt(staticTiming.hFrontPorch)),
      "hsync-len" -> Seq(ResourceInt(staticTiming.hSyncLen)),
      "hback-porch" -> Seq(ResourceInt(staticTiming.hBackPorch)),
      "vfront-porch" -> Seq(ResourceInt(staticTiming.vFrontPorch)),
      "vsync-len" -> Seq(ResourceInt(staticTiming.vSyncLen)),
      "vback-porch" -> Seq(ResourceInt(staticTiming.vBackPorch)),
      "hsync-active" -> Seq(ResourceInt(if (staticTiming.hSyncPositive) 1 else 0)),
      "vsync-active" -> Seq(ResourceInt(if (staticTiming.vSyncPositive) 1 else 0))
    )
  )
  AxiSlaveBinder.bindSimpleDevice(devname = "vtc0", dts = vtcDts, perms = AxiSlaveBinder.mmioPerms)

  // The console framebuffer: a fixed carve-out the soct-dp kernel module parks the scanout
  // on, plus its kernel-facing description. The reservation keeps the kernel's allocator
  // away; `no-map` keeps it out of the linear map so the framebuffer driver's mapping is
  // the one mapping. It is emitted for every video variant - who serves it as a
  // framebuffer device differs (see the /chosen node below).
  private val fbReserved = new SimpleDevice("framebuffer", Nil) {
    override def parent: Some[Device] = Some(reservedMemoryDev)
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping ++ Map("no-map" -> Nil))
    }
  }
  ResourceBinding {
    Resource(fbReserved, "reg").bind(ResourceAddress(
      AddressSets.fromOffsetRange(ZynqUltraPS.VideoFbBase.toLong, ZynqUltraPS.VideoFbSize.toLong),
      AxiSlaveBinder.mmioPerms))
  }

  // The `simple-framebuffer` node (under /chosen - the one place the kernel looks for a
  // pre-described framebuffer, see of_platform_default_populate). The kernel's simplefb
  // driver adopts the buffer as-is and fbcon renders the console into it; the soct-dp
  // module starts the scanout that puts it on the monitor.
  // `r8g8b8` is the byte order the fabric fixes: blue, green, red from the low address up.
  // Coherent-fetch designs only: simplefb writes through the CPU caches and flushes
  // nothing, which an incoherent scanout would never see. There the soct-dp module
  // serves the carve-out itself, through a framebuffer device that flushes what it draws.
  if (!vs.incoherent) {
    val fbDev = new SimpleDevice("framebuffer", Seq("simple-framebuffer")) {
      override def parent: Some[Device] = Some(chosenDev)
      override def describe(resources: ResourceBindings): Description = {
        val Description(name, mapping) = super.describe(resources)
        Description(name, mapping ++ Map(
          "width" -> Seq(ResourceInt(vs.width)),
          "height" -> Seq(ResourceInt(vs.height)),
          "stride" -> Seq(ResourceInt(vs.width * 3)),
          "format" -> Seq(ResourceString("r8g8b8"))
        ))
      }
    }
    ResourceBinding {
      Resource(fbDev, "reg").bind(ResourceAddress(
        AddressSets.fromOffsetRange(ZynqUltraPS.VideoFbBase.toLong, ZynqUltraPS.VideoFbSize.toLong),
        AxiSlaveBinder.mmioPerms))
    }
  }

  // Read-only status of the video-out core, which has no register interface of its own:
  // {bit2 overflow, bit1 underflow, bit0 locked} at offset 0x0. Drivers poll locked and
  // underflow to detect a starving or unlocked stream.
  private val vidStatusDts = DTSInfo(
    parent = mmioBus,
    regs = Seq(("reg", VivadoMmioMap.VideoStatusBase, VivadoMmioMap.RegionSize)),
    compatibles = Seq("soct,video-status", "xlnx,xps-gpio-1.00.a")
  )
  AxiSlaveBinder.bindSimpleDevice(devname = "vidstat0", dts = vidStatusDts, perms = AxiSlaveBinder.mmioPerms)

  // Every fragment offering a pipeline records itself, so asking for two different ones
  // is a fact about the parameters rather than a guess from the design's name: whichever
  // won HasVideoStream would be built, silently, while the other still contributed its
  // name suffix. Report what was actually asked for.
  locally {
    val requested = p(VideoStreamRequests).distinct
    if (requested.size > 1)
      throw VivadoDesignException(
        s"The design configures the video pipeline more than once (${requested.mkString("; ")}). " +
          "The video fragments are alternatives - exactly one belongs in a design.")
  }

  // An incoherent frame fetch shifts the visibility of rendered pixels onto software, and
  // the ONLY mechanism this core offers is the L2's Flush64 register - it has no
  // cache-maintenance instructions. Without an L2 the best software can do is read enough
  // addresses to provoke eviction, which a randomly-replaced cache does not guarantee:
  // some dirty lines stay behind and scan out as stale blocks. Refuse the combination
  // here rather than generate a design whose display is correct only by chance.
  if (vs.incoherent && p.lift(InclusiveCacheKey).isEmpty)
    throw VivadoDesignException(
      "Incoherent video (soct.WithIncoherentVideoStream) needs an L2: software must flush " +
        "rendered frames before the private frame fetch reads them, and the L2's Flush64 " +
        "register is the only way to do that on this core. Add --with-config soct.WithL2Cache, " +
        "or use the coherent pipeline (soct.WithVideoStream), which needs no flushing.")

  // The framebuffer carve-out is fixed; a mode that does not fit it can never scan out.
  if (vs.width.toLong * vs.height * 3 > ZynqUltraPS.VideoFbSize.toLong)
    throw VivadoDesignException(
      s"Video mode ${vs.width}x${vs.height} needs ${vs.width.toLong * vs.height * 3} bytes of framebuffer, " +
        s"but the reserved carve-out holds ${ZynqUltraPS.VideoFbSize.toLong} (ZynqUltraPS.VideoFbSize).")

  // The runtime-retunable pixel clock (incoherent designs; the hardware side is in
  // wireMain): the clocking wizard's reconfiguration registers plus the retune BUDGET -
  // the MMCM's input clock, its analog window, and the ceiling timing was closed at.
  // Deliberately NOT a list of modes: whether a timing works is the monitor's call
  // (sinks freely refuse modes no table can predict), so software synthesizes standard
  // timings on request and solves the dividers against these facts, refusing only what
  // this hardware itself cannot do.
  if (vs.incoherent) {
    val staticClock = VideoStreamFeature.pixelClockFor(vs)
    // The ceiling itself must be synthesizable - fail at generation, not on the board.
    MmcmSolver.solve(input = p(PeripheryClockDomain).freq, target = staticClock)
    val pixClkDts = DTSInfo(
      parent = mmioBus,
      regs = Seq(("reg", VivadoMmioMap.PixelClkBase, VivadoMmioMap.RegionSize)),
      compatibles = Seq("soct,pixel-clkwiz"),
      extraProps = Map(
        // The wizard's clk_in1: what the runtime solver's dividers act on.
        "soct,input-frequency" ->
          Seq(ResourceInt(BigInt(p(PeripheryClockDomain).freq.toHz.toLong))),
        // Timing closure ran at the synthesized mode's clock; retunes only go down.
        "soct,max-frequency" -> Seq(ResourceInt(BigInt(staticClock.toHz.toLong))),
        // The MMCME4 analog window (DS925) - a speed-grade fact the generator knows
        // and a runtime solver must not guess.
        "soct,vco-min" -> Seq(ResourceInt(BigInt(MmcmSolver.VcoMin.toHz.toLong))),
        "soct,vco-max" -> Seq(ResourceInt(BigInt(MmcmSolver.VcoMax.toHz.toLong))),
        "soct,pfd-min" -> Seq(ResourceInt(BigInt(MmcmSolver.PfdMin.toHz.toLong))),
        "soct,pfd-max" -> Seq(ResourceInt(BigInt(MmcmSolver.PfdMax.toHz.toLong)))
      )
    )
    AxiSlaveBinder.bindSimpleDevice(devname = "pixclk0", dts = pixClkDts,
      perms = AxiSlaveBinder.mmioPerms)
  }

  override def claimedIrqs: Seq[Irq] = Seq(vdmaIrq)

  /**
   * The pixel clock domain of the configured mode, implied by its timing
   * ([[soct.vivado.misc.VideoTiming]]).
   */
  private def pixelDomainFor(vs: VideoStreamParams): ClockDomain =
    new ClockDomain(VideoStreamFeature.pixelClockFor(vs))

  override def wireMain(ctx: FeatureWireContext): Unit = {
    val c = ctx.c
    val peripheryClock = ctx.peripheryClock
    val ps = bd.fpgaInstance() match {
      case fpga: HasZynqUltraPS => fpga.getZynqUltraPS()
      case fpga => throw VivadoDesignException(
        s"Video stream requires a Zynq UltraScale+ PS (the pipeline drives its DisplayPort " +
          s"controller), but board ${fpga.friendlyName} has none.")
    }

    // Incoherent frame fetch: the VDMA masters the memory-side SmartConnect directly, so it
    // reaches DRAM without crossing the coherent fabric (see [[soct.WithIncoherentVideoStream]]).
    // Only meaningful with exactly one memory channel: with several, memSMC sits behind the
    // address deinterleaver and sees one channel's dense address space, so a framebuffer would
    // have to be pinned to that channel - fail instead of mapping frames to the wrong DRAM.
    val memPathOpt = if (!vs.incoherent) None else Some(ctx.memPaths match {
      case Seq(single) => single
      case several => throw VivadoDesignException(
        s"Incoherent video needs exactly one memory channel, but the design has ${several.length}: " +
          "the memory SmartConnect is behind the address deinterleaver and exposes only its own " +
          "channel, so frames would be fetched from the wrong DRAM. Use the coherent video " +
          "pipeline (soct.WithVideoStream) or a single-channel memory layout.")
    })

    // Components - the whole PL-side pipeline lives in the `video` BD hierarchy (the PS
    // and the pixel reset synchronizer stay outside: board-level and its own block).
    // Coherent: the frame master targets the Rocket L2-frontend AXI slave. Incoherent: it has
    // no fabric slave to target - its address space is mapped straight onto the DDR4
    // controller's memory segment below, so the DMA never enters the SoC's interconnect.
    val vdma = memPathOpt match {
      case None => AXIVideoDMA(vdmaDts, c.axiMMIO, Seq((c.axiDMA, "reg0"))).withGroup("video")
      case Some(mem) =>
        val ddr4 = mem.ddr4Inst
        new AXIVideoDMA(vdmaDts, c.axiMMIO, Seq.empty) {
          override def assignAddrTcl: TCLCommands = super.assignAddrTcl ++ Seq(
            s"""assign_bd_address -offset 0x00000000 -range 0x${dmaMasterRange.toHexString.toUpperCase} -target_address_space [get_bd_addr_spaces $bdPath/Data_MM2S] [get_bd_addr_segs ${ddr4.bdPath}/C0_DDR4_MEMORY_MAP/C0_DDR4_ADDRESS_BLOCK]
               |# Same 'register' vs 'memory' usage mismatch as the coherent path: re-include the
               |# segment Vivado excluded as a precaution (BD 41-1051).
               |include_bd_addr_seg [get_bd_addr_segs -excluded -of_objects [get_bd_addr_spaces $bdPath/Data_MM2S]]""".stripMargin.tcl
          )
          // An anonymous subclass has no class name to snake-case, so name it explicitly -
          // otherwise the instance (and every path derived from it) degenerates to "_0".
        }.withInstanceName("axivideo_dma").withGroup("video")
    }
    val vtc = VideoTimingController(vtcDts, c.axiMMIO).withGroup("video")
    val vidOut = AxisVideoOut().withGroup("video")

    // Pixel clock: synthesized from the periphery clock, since no board clock matches video rates
    val pixelDomain = pixelDomainFor(vs)
    val pixClkWiz = ClkWiz(inputDom = Some(c.peripheryDomain), dynReconfig = vs.incoherent)
      .withInstanceName("pixel_clk_wiz").withGroup("video")
    peripheryClock --> pixClkWiz.CLK_IN.next()
    // With dynamic reconfiguration the core has no standalone `reset` input - its reset
    // is s_axi_aresetn, wired with the reconfiguration interface below.
    if (!vs.incoherent) c.periphPsr.PeripheralReset --> pixClkWiz.RESET
    val pixelClock = pixClkWiz.CLK_OUT(1, pixelDomain)

    // Clocks: control and memory sides on the periphery domain; the whole video path - the
    // VDMA's pixel stream, the video out, the timing generator and the PS live input - on
    // the pixel domain. The stream must carry one pixel per cycle at the full pixel rate;
    // on the (slower) periphery clock it starves the video out mid-line.
    peripheryClock --> Seq(vdma.S_AXI_LITE_ACLK, vtc.S_AXI_ACLK, vdma.M_AXI_MM2S_ACLK)
    pixelClock --> Seq(vdma.M_AXIS_MM2S_ACLK, vidOut.ACLK, vtc.CLK, vidOut.VID_IO_OUT_CLK,
      ps.DP_VIDEO_IN_CLK)

    // Pixel-domain reset: held while the periphery resets or the pixel MMCM is unlocked.
    // The external reset input MUST be fed active-low here: it arrives through a
    // polarity-stripping slice and Vivado then infers the (read-only) pin polarity as
    // ACTIVE_LOW regardless of the source - feeding the active-high PeripheralReset held
    // this domain in permanent reset (verified on hardware and by C_EXT_RESET_HIGH
    // readback; see the warning on [[soct.vivado.components.ProcSysReset]]).
    val pixelPsr = ProcSysReset().withInstanceName("pixel_psr").withGroup("pixel_reset")
    pixelClock --> pixelPsr.SLOWEST_SYNC_CLK
    pixClkWiz.LOCKED --> pixelPsr.DCM_LOCKED
    c.periphPsr.PeripheralAResetN --> pixelPsr.EXT_RESET_IN

    // Resets and enables. The video cores are held out of reset permanently after that:
    // they only produce garbage until the driver programs VDMA/VTC, which is harmless.
    c.periphPsr.PeripheralAResetN --> Seq(vdma.AXI_RESETN, vtc.S_AXI_ARESETN)
    pixelPsr.PeripheralAResetN --> vidOut.ARESETN
    TieHigh().withInstanceName("video_enables_high").withGroup("video") --> Seq(vtc.CLKEN, vtc.RESETN, vidOut.ACLKEN, vidOut.VID_IO_OUT_CE)
    TieOff().withInstanceName("video_ties_low").withGroup("video") --> Seq(vidOut.VID_IO_OUT_RESET, vtc.FSYNC_IN)

    // Stream and timing path
    vdma.M_AXIS_MM2S <-> vidOut.VIDEO_IN
    vtc.VTIMING_OUT <-> vidOut.VTIMING_IN
    vidOut.VTG_CE --> vtc.GEN_CLKEN

    // AXI: control registers on the MMIO path, frame reads on the DMA path. The DP controller's
    // own registers are reached through the shared PS window (see PsWindowFeature).
    c.mmioSMC.M_AXI.next() <-> vdma.S_AXI
    c.mmioSMC.M_AXI.next() <-> vtc.S_AXI
    memPathOpt match {
      case None => c.dmaSMC.S_AXI.next() <-> vdma.M_AXI
      case Some(mem) =>
        // The periphery-clocked private port on the (core + DDR)-clocked memory
        // SmartConnect: hand it the periphery clock so the crossing is synchronous
        // inside the SmartConnect.
        peripheryClock --> mem.memSMC.ACLK.next()
        mem.memSMC.S_AXI.next() <-> vdma.M_AXI
    }

    // Video pipeline status readable by software: {bit2 overflow, bit1 underflow,
    // bit0 locked} of the video out - the operational health of the stream (drivers poll
    // locked/underflow to detect starvation).
    val vidStatus = AxiGpio(vidStatusDts, c.axiMMIO, ch1Width = 3)
      .withInstanceName("video_status_gpio").withGroup("video")
    c.mmioSMC.M_AXI.next() <-> vidStatus.S_AXI
    peripheryClock --> vidStatus.S_AXI_ACLK
    c.periphPsr.PeripheralAResetN --> vidStatus.S_AXI_ARESETN
    val statusBits = InlineConcat(3).withInstanceName("vid_status_concat").withGroup("video")
    vidOut.LOCKED --> statusBits.IN(0)
    vidOut.UNDERFLOW --> statusBits.IN(1)
    vidOut.OVERFLOW --> statusBits.IN(2)
    statusBits --> vidStatus.GPIO_IO_I

    // Dynamic pixel-clock reconfiguration (see the pixclk0 device-tree node above):
    // software retunes the MMCM through the wizard's AXI4-Lite registers to switch
    // video modes without a new bitstream. The pixel-domain reset already rides
    // LOCKED (pixel_psr.DCM_LOCKED), so the domain resets itself across a retune.
    if (vs.incoherent) {
      peripheryClock --> pixClkWiz.S_AXI_ACLK
      c.periphPsr.PeripheralAResetN --> pixClkWiz.S_AXI_ARESETN
      c.mmioSMC.M_AXI.next() <-> pixClkWiz.S_AXI_LITE
      bd.addConfigTcl(() => Seq(
        (s"assign_bd_address -offset 0x${VivadoMmioMap.PixelClkBase.toHexString.toUpperCase}" +
          s" -range 0x${VivadoMmioMap.RegionSize.toHexString.toUpperCase}" +
          s" [get_bd_addr_segs ${pixClkWiz.bdPath}/s_axi_lite/Reg]").tcl))
    }

    // Interrupt (a level, held until the driver clears DMASR - INTC input configured as
    // level accordingly; the DTS carries it on the VDMA's channel child node)
    vdma.MM2S_INTROUT --> c.interruptConcat.IN(vdmaIrq.index)

    // Parallel video into the PS live input. The PS wants 12 bit per component (36-bit
    // pixel); the stream carries 8 bit per component (24-bit), so each component is padded
    // with 4 zero LSBs.
    //
    // This mapping DEFINES the framebuffer's byte order, because the VDMA reads the 24-bit
    // word little-endian: stream bits [7:0] are the byte at the lower address. The bytes are
    // routed so that a pixel reads blue, green, red from the low address up - `r8g8b8` in the
    // Linux and DRM naming, and the only 24-bit layout either of them defines. A framebuffer
    // built here can therefore be handed to a generic driver (a `simple-framebuffer` node, for
    // one) without a translation step or a private format.
    val byte2 = InlineSlice(24, 23, 16, 8).withInstanceName("vid_slice_byte2").withGroup("video")
    val byte1 = InlineSlice(24, 15, 8, 8).withInstanceName("vid_slice_byte1").withGroup("video")
    val byte0 = InlineSlice(24, 7, 0, 8).withInstanceName("vid_slice_byte0").withGroup("video")
    val zero4 = InlineConstant(0, 4).withInstanceName("vid_pad_zero4").withGroup("video")
    val pixel = InlineConcat(6).withInstanceName("vid_pixel_concat").withGroup("video")

    // Which concat position drives which colour is fixed by the PS: position 1 is the
    // component the DisplayPort shows as green, 3 as blue, 5 as red. Feeding byte1 to green
    // and byte0 to blue is what puts blue at the lowest address.
    Seq(byte2, byte1, byte0).foreach(s => vidOut.VID_DATA --> s.DIN)
    zero4.DOUT --> Seq(pixel.IN(4), pixel.IN(2), pixel.IN(0))
    byte2.DOUT --> pixel.IN(5) // red
    byte1.DOUT --> pixel.IN(1) // green
    byte0.DOUT --> pixel.IN(3) // blue

    pixel --> ps.DP_LIVE_VIDEO_IN_PIXEL1
    vidOut.VID_ACTIVE_VIDEO --> ps.DP_LIVE_VIDEO_IN_DE
    vidOut.VID_HSYNC --> ps.DP_LIVE_VIDEO_IN_HSYNC
    vidOut.VID_VSYNC --> ps.DP_LIVE_VIDEO_IN_VSYNC
  }
}

/** Presence decision and mode-timing helpers of [[VideoStreamFeature]]. */
object VideoStreamFeature {
  /**
   * The timing of a configured video mode: exact CEA-861 for the modes that standard
   * defines, VESA CVT reduced blanking computed for any other resolution - the
   * configuration is free, not limited to a table (see [[soct.vivado.misc.VideoTiming]]).
   */
  def timingFor(vs: VideoStreamParams): VideoTiming =
    VideoTiming.forMode(vs.width, vs.height, vs.fps)

  /** The pixel clock of a configured video mode, implied by its timing. */
  def pixelClockFor(vs: VideoStreamParams): Freq = timingFor(vs).pixelClock

  /** The single presence decision: `Some` iff the design has a video stream ([[HasVideoStream]]). */
  def ifPresent(mmioBus: Device, intcDev: Device, irqs: IrqAllocator,
                chosenDev: Device, reservedMemoryDev: Device)
               (implicit p: Parameters, bd: SOCTBdBuilder): Option[VideoStreamFeature] =
    p(HasVideoStream).map(vs =>
      new VideoStreamFeature(vs, mmioBus, intcDev, irqs, chosenDev, reservedMemoryDev))
}
