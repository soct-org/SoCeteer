package soct.system.vivado

import freechips.rocketchip.resources.{Description, Device, DeviceSnippet, FixedClockResource, Resource, ResourceAddress, ResourceBinding, ResourceBindings, ResourceInt, ResourceReference, ResourceString, SimpleDevice}
import soct._
import soct.system.vivado.components.AXIVideoDMA
import soct.system.vivado.misc.{AddressSets, AxiSlaveBinder, DTSInfo, Irq}

/**
 * The device-tree half of [[SOCTVivadoSystemBase]] (one file per concern: TCL timing
 * helpers in [[SOCTVivadoSystemConstraints]], components and wiring in
 * [[SOCTVivadoSystemWiring]]). Everything here runs at CONSTRUCTION time - resources must
 * be bound before module instantiation - and in a fixed order: [[irqIdx]] hands out INTC
 * inputs sequentially (uart, sd, vdma), so the blocks below must not be reordered.
 */
trait SOCTVivadoSystemDTS {
  this: SOCTVivadoSystemBase =>

  // First initialized member on purpose (this trait is mixed in first): the builder
  // gate fires before any resource binding, exactly as it did pre-split.
  implicit val bd: SOCTBdBuilder = p(BdBuilderKey).getOrElse(
    throw new VivadoDesignException("SOCTVivadoSystemBase requires a BdBuilder to be set in parameters for block design generation.")
  )

  private val plicDev = plicOpt.getOrElse(
    throw new VivadoDesignException("SOCTVivadoSystemBase requires a PLIC to be present in the system for interrupt wiring.")
  ).device

  /** Next free INTC input; bumped for every bound MMIO device that raises interrupts. */
  protected var irqIdx = 0

  /** Bitmask of INTC inputs carrying edge/pulse interrupts (bit i = input i; unset bits
   * are levels). Accumulated while the devices below claim their inputs; read at DTS
   * emission time and by the INTC instantiation, when it is final. Same encoding as the
   * IP's C_KIND_OF_INTR and the device tree's xlnx,kind-of-intr. */
  protected var intcEdgeMask = 0

  /** Register base of the AXI interrupt controller. */
  private val intcBase = 0x60050000L

  /**
   * The AXI interrupt controller's device-tree node: every fabric peripheral cascades its
   * interrupt through it into the PLIC (see [[soct.system.vivado.components.AXIIntc]] for
   * why the PLIC cannot take the peripherals directly). Created before the peripherals -
   * they name it as their interrupt parent - while its input-dependent properties are
   * computed in [[freechips.rocketchip.resources.Device.describe]], which runs at DTS
   * emission when every input is claimed.
   */
  protected val intcDev: SimpleDevice = new SimpleDevice("interrupt-controller", Seq("xlnx,xps-intc-1.00.a")) {
    override def parent: Some[Device] = Some(mmioBusDevice.get)
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping ++ Map(
        "interrupt-controller" -> Nil,
        // Two cells per interrupt; the Xilinx binding documents the second as unused
        // (trigger types are hardware configuration, carried by xlnx,kind-of-intr).
        "#interrupt-cells" -> Seq(ResourceInt(2)),
        "xlnx,num-intr-inputs" -> Seq(ResourceInt(irqIdx)),
        "xlnx,kind-of-intr" -> Seq(ResourceInt(intcEdgeMask))
      ))
    }
  }

  /** UART register base and fixed baud; the DTS `reg`, `current-speed` and the
   * `/chosen` boot arguments below must all agree with the IP configuration
   * ([[soct.system.vivado.components.AXIUartLite]] sets C_BAUDRATE to the same value). */
  private val uartBase = 0x60010000L
  private val uartBaud = 115200

  /** Device-tree entry of the UART, if the design has one ([[HasUART]]). */
  protected val uartDTSOpt: Option[DTSInfo] = if (p(HasUART)) {
    val dts = DTSInfo(
      parent = mmioBusDevice.get,
      regs = Seq(("reg", uartBase, 0x10000L)),
      irqs = Seq(Irq(intcDev, irqIdx)),
      // The soct compatible first (soctglue matches it); the Xilinx one second, so
      // Linux's uartlite driver (SERIAL_UARTLITE) binds the console to this UART.
      compatibles = Seq("riscv,axi-uart-1.0", "xlnx,xps-uartlite-1.00.a"),
      // current-speed is REQUIRED by the Linux uartlite driver (the baud is fixed at
      // synthesis, so the driver refuses to guess): without it the probe fails with
      // -EINVAL and the console never comes up.
      extraProps = Map(
        "port-number" -> Seq(ResourceInt(0)),
        "current-speed" -> Seq(ResourceInt(uartBaud))
      )
    )
    // The UART Lite interrupt is a one-clock PULSE per FIFO transition (PG142) - the
    // INTC must latch it as an edge or it is lost (hardware-diagnosed console wedge).
    intcEdgeMask |= 1 << irqIdx
    irqIdx += 1
    Some(dts)
  } else None

  uartDTSOpt.foreach { dts =>
    AxiSlaveBinder.bindSimpleDevice(
      devname = "uart0",
      dts = dts,
      perms = AxiSlaveBinder.mmioPerms
    )
  }

  // The /chosen node: boot arguments for an operating system. The console selection and
  // the early console describe THIS design's UART, so they belong in the device tree the
  // design emits - not baked into a kernel binary, which would tie the kernel image to
  // one hardware generation. Only emitted when the design has a UART to talk through.
  uartDTSOpt.foreach { _ =>
    val chosenDev = new Device {
      def describe(resources: ResourceBindings): Description =
        Description("chosen", Map("bootargs" -> resources("bootargs").map(_.value)))
    }
    ResourceBinding {
      Resource(chosenDev, "bootargs").bind(ResourceString(
        s"console=ttyUL0,$uartBaud earlycon=uartlite,mmio,0x${uartBase.toHexString}"))
    }
  }

  /** Device-tree entry of the SD-card controller, if the design has one ([[HasSDCardPMOD]]). */
  protected val sdDTSOpt: Option[DTSInfo] = p(HasSDCardPMOD).map { idx =>
    // The controller divides the periphery clock, so the DTS must carry the ACTUAL
    // frequency (the driver derives every SD rate from it) - not a hardcoded value that
    // silently goes stale when the domain is reconfigured. The fastest reachable SD clock
    // is clock/2 (minimum divider), which is also what the driver would derive on its
    // own; anything higher underflows its divider computation.
    val periphHz = p(PeripheryClockDomain).toHz.toLong
    val sdDTS = DTSInfo(
      parent = mmioBusDevice.get,
      regs = Seq(("reg", 0x60000000L, 0x10000L)),
      irqs = Seq(Irq(intcDev, irqIdx)),
      compatibles = Seq("riscv,axi-sd-card-1.0"),
      extraProps = Map(
        "clock" -> Seq(ResourceInt(BigInt(periphHz))),
        "bus-width" -> Seq(ResourceInt(4)),
        "fifo-depth" -> Seq(ResourceInt(256)),
        "max-frequency" -> Seq(ResourceInt(BigInt(periphHz / 2))),
        "cap-sd-highspeed" -> Nil,
        "cap-mmc-highspeed" -> Nil,
        // No `cd-inverted` here on purpose: the controller instance is configured for
        // the PMOD's active-low detect switch (sdio_card_detect_level = 0, see
        // SDCardPMOD), so the presence level reads true. The property (and the sdc
        // driver's support for it) exists for hardware where the two disagree.
        "no-sdio" -> Nil
      )
    )
    irqIdx += 1
    AxiSlaveBinder.bindSimpleDevice(
      devname = "mmc0",
      dts = sdDTS,
      perms = AxiSlaveBinder.mmioPerms
    )
    sdDTS
  }

  /** Device-tree entries of the DisplayPort video pipeline (see [[videoDTSOpt]]).
   *
   * @param vdmaIrq the VDMA frame-transfer interrupt's INTC input, for the concat wiring
   *                (in the DTS it lives on the VDMA's channel CHILD node, not in
   *                `vdma.irqs`, following the mainline binding)
   */
  protected case class VideoStreamDTS(vdma: DTSInfo, vtc: DTSInfo, dpWindow: DTSInfo,
                                      vidStatus: DTSInfo, vdmaIrq: Irq)

  /**
   * Device-tree entries of the DisplayPort video pipeline, if the design has one
   * ([[HasVideoStream]]): the VDMA control registers, the video timing controller, and the
   * window through which the PS DP registers are reached (see
   * [[soct.system.vivado.components.AxiAddrOffset]]).
   *
   * The VDMA node follows the mainline `xlnx,axi-dma.yaml` binding exactly, so the stock
   * dmaengine driver (CONFIG_XILINX_DMA) probes it: the controller node carries
   * `#dma-cells`, addressing, `dma-ranges`, `xlnx,num-fstores` and a clock reference
   * (`s_axi_lite_aclk` is the one clock the driver refuses to probe without - emitted
   * here as a fixed-clock, since the periphery clock is fixed in hardware); the interrupt
   * and `xlnx,datawidth` sit on a `dma-channel` CHILD node per the binding.
   */
  protected val videoDTSOpt: Option[VideoStreamDTS] = p(HasVideoStream).map { vs =>
    val periphHz = p(PeripheryClockDomain).toHz.toLong
    val vdmaDTS = DTSInfo(
      parent = mmioBusDevice.get,
      regs = Seq(("reg", 0x60020000L, 0x10000L)),
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
      ) ++ (if (!vs.incoherent) Map.empty else Map(
        // The frame master reaches DRAM through its own memory-controller port, bypassing the
        // coherent fabric (soct.WithIncoherentVideoStream): DRAM is NOT coherent with the CPU
        // caches for these frames, so software must make rendered pixels visible before the
        // DMA reads them (L2 Flush64 where an L2 exists, an L1 eviction otherwise). Marked so
        // a driver can select that contract instead of assuming coherent DMA.
        "soct,incoherent" -> Nil
      ))
    )
    val vdmaDev = AxiSlaveBinder.bindSimpleDevice(devname = "dma-controller", dts = vdmaDTS,
      perms = AxiSlaveBinder.mmioPerms)
    new FixedClockResource("periph_clock", periphHz / 1e6).bind(vdmaDev)

    val vdmaIrq = Irq(intcDev, irqIdx)
    irqIdx += 1
    val vdmaChanDev = new SimpleDevice("dma-channel", Seq("xlnx,axi-vdma-mm2s-channel")) {
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

    // The video mode is baked into the design (pixel clock); advertise it so the driver
    // programs matching timing without hardcoding a resolution.
    val vtcDTS = DTSInfo(
      parent = mmioBusDevice.get,
      regs = Seq(("reg", 0x60030000L, 0x10000L)),
      compatibles = Seq("xlnx,v-tc-6.2"),
      extraProps = Map(
        "soct,hactive" -> Seq(ResourceInt(vs.width)),
        "soct,vactive" -> Seq(ResourceInt(vs.height)),
        "soct,fps" -> Seq(ResourceInt(vs.fps))
      )
    )
    AxiSlaveBinder.bindSimpleDevice(devname = "vtc0", dts = vtcDTS, perms = AxiSlaveBinder.mmioPerms)

    // The DP/DPDMA/SERDES registers of the PS, visible through the address-offset window.
    // soct,ps-base carries the fixed PS base the window maps to, so the driver can translate
    // documented PS addresses without magic numbers.
    val dpWindowDTS = DTSInfo(
      parent = mmioBusDevice.get,
      regs = Seq(("reg", 0x7D000000L, 0x1000000L)),
      compatibles = Seq("soct,zynqmp-dp-window"),
      extraProps = Map("soct,ps-base" -> Seq(ResourceInt(0xFD000000L)))
    )
    AxiSlaveBinder.bindSimpleDevice(devname = "dpwin0", dts = dpWindowDTS, perms = AxiSlaveBinder.mmioPerms)

    // Read-only status of the video-out core, which has no register interface of its own:
    // {bit2 overflow, bit1 underflow, bit0 locked} at offset 0x0. Drivers poll locked and
    // underflow to detect a starving or unlocked stream.
    val vidStatusDTS = DTSInfo(
      parent = mmioBusDevice.get,
      regs = Seq(("reg", 0x60040000L, 0x10000L)),
      compatibles = Seq("soct,video-status", "xlnx,xps-gpio-1.00.a")
    )
    AxiSlaveBinder.bindSimpleDevice(devname = "vidstat0", dts = vidStatusDTS, perms = AxiSlaveBinder.mmioPerms)

    VideoStreamDTS(vdmaDTS, vtcDTS, dpWindowDTS, vidStatusDTS, vdmaIrq)
  }

  /**
   * The INTC's own device-tree resources, bound after every peripheral has claimed its
   * input (the input count and edge mask are final only then): the register region and
   * the single level line into the PLIC. None when no device raises interrupts - the
   * design then has no INTC at all and the core's external interrupt is tied off.
   */
  protected val intcDTSOpt: Option[DTSInfo] = if (irqIdx > 0) {
    val dts = DTSInfo(
      parent = mmioBusDevice.get,
      regs = Seq(("reg", intcBase, 0x10000L)),
      compatibles = Seq("xlnx,xps-intc-1.00.a")
    )
    ResourceBinding {
      dts.regs.foreach { case (name, offset, rangeBytes) =>
        Resource(intcDev, s"reg/$name").bind(
          ResourceAddress(AddressSets.fromOffsetRange(offset, rangeBytes), AxiSlaveBinder.mmioPerms))
      }
      // PLIC sources are 1-based (source 0 is reserved "no interrupt"): external-interrupt
      // vector position 0 - the only one, see WithNExtTopInterrupts(1) - is source 1.
      Resource(intcDev, "int").bind(plicDev, ResourceInt(1))
    }
    Some(dts)
  } else None

  /**
   * The system-reset register: a 1-bit write-only GPIO whose output is ORed into the
   * external reset of the core and periphery reset synchronizers (see [[wireDebugReset]]) -
   * the same net the JTAG `reset_core` flow pulses, so a software reboot has identical
   * semantics: core + periphery restart, DDR keeps its calibration, the boot ROM reloads
   * BOOT.ELF. The register clears itself, being reset by the very reset it triggers.
   *
   * In the device tree it appears as a `syscon` node plus a `syscon-reboot` companion:
   * OpenSBI's generic platform (FDT_RESET_SYSCON) turns that pair into an SBI SRST
   * backend, so Linux reboots through the SBI with no kernel driver at all.
   */
  protected val sysResetDTS: DTSInfo = {
    val dts = DTSInfo(
      parent = mmioBusDevice.get,
      regs = Seq(("reg", 0x60060000L, 0x10000L)),
      compatibles = Seq("syscon")
    )
    val dev = AxiSlaveBinder.bindSimpleDevice(devname = "sysreset", dts = dts,
      perms = AxiSlaveBinder.mmioPerms)
    new DeviceSnippet {
      def describe() = Description("soc/reboot", Map(
        "compatible" -> Seq(ResourceString("syscon-reboot")),
        "regmap" -> Seq(ResourceReference(dev.label)),
        "offset" -> Seq(ResourceInt(0)),
        "value" -> Seq(ResourceInt(1)),
        "mask" -> Seq(ResourceInt(1))
      ))
    }
    dts
  }
}
