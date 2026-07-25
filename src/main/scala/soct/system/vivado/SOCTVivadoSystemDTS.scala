package soct.system.vivado

import freechips.rocketchip.resources.{Description, Device, DeviceSnippet, Resource, ResourceAddress, ResourceBinding, ResourceBindings, ResourceInt, ResourceReference, ResourceString, SimpleDevice}
import soct._
import soct.vivado._
import soct.system.vivado.features.{IrqAllocator, PsWindowFeature, SdCardFeature, UartFeature, UsbHostFeature, VideoStreamFeature, VivadoFeature, VivadoMmioMap}
import soct.vivado.fpga.HasZynqUltraPS
import soct.vivado.misc.{AddressSets, AxiSlaveBinder, DTSInfo}

/**
 * The device-tree half of [[SOCTVivadoSystemBase]] (one file per concern: TCL timing
 * helpers in [[SOCTVivadoSystemConstraints]], components and wiring in
 * [[SOCTVivadoSystemWiring]]): the shared device-tree infrastructure (INTC, /chosen,
 * /reserved-memory, syscon reset) and the construction sequence of the optional
 * [[soct.system.vivado.features.VivadoFeature]]s, each of which binds its own nodes.
 *
 * Everything here runs at CONSTRUCTION time - resources must be bound before module
 * instantiation - and in a FIXED ORDER: [[irqs]] hands out INTC inputs in claim order
 * (uart, sd, vdma, usb), and the device-tree labels (`L<n>`) follow the global Device
 * construction order, so the members below must not be reordered (the assertion under
 * [[features]] catches an interrupt renumbering, label drift it cannot see).
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

  /** INTC input allocator: every bound MMIO device that raises interrupts claims its
   * input here, in construction order. */
  protected val irqs = new IrqAllocator

  /**
   * The AXI interrupt controller's device-tree node: every fabric peripheral cascades its
   * interrupt through it into the PLIC (see [[soct.vivado.components.AXIIntc]] for
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
        "xlnx,num-intr-inputs" -> Seq(ResourceInt(irqs.count)),
        "xlnx,kind-of-intr" -> Seq(ResourceInt(irqs.edgeMask))
      ))
    }
  }

  /** The console UART, if the design has one ([[HasUART]]); constructing it binds its
   * device-tree resources and claims INTC input 0. */
  protected val uartFeature: Option[UartFeature] =
    UartFeature.ifPresent(mmioBusDevice.get, intcDev, irqs)

  /**
   * The /chosen node: what the boot environment tells an operating system, as opposed to what
   * the hardware is. Boot arguments bind to it below (UART designs), and video designs hang
   * their `framebuffer` node under it (see [[VideoStreamFeature]]) - under /chosen because a
   * boot-stage-initialized framebuffer is exactly that, environment rather than hardware.
   * A device with no bound resources and no children is not emitted at all.
   */
  protected val chosenDev: Device = new Device {
    def describe(resources: ResourceBindings): Description = {
      val bootargs = resources("bootargs").map(_.value)
      Description("chosen", Map(
        // Cells + ranges for the framebuffer child's reg; harmless when only bootargs bind.
        "#address-cells" -> Seq(ResourceInt(2)),
        "#size-cells" -> Seq(ResourceInt(2)),
        "ranges" -> Nil
      ) ++ (if (bootargs.nonEmpty) Map("bootargs" -> bootargs) else Map.empty))
    }
  }

  /**
   * The /reserved-memory container: regions of DRAM the kernel must not allocate. A device
   * tree has exactly one such node, so every carve-out below (the USB DMA pool, the scanout
   * framebuffer) names this device as its parent. Not emitted while no child binds anything.
   */
  protected val reservedMemoryDev: Device = new Device {
    def describe(resources: ResourceBindings): Description =
      Description("reserved-memory", Map(
        "#address-cells" -> Seq(ResourceInt(2)),
        "#size-cells" -> Seq(ResourceInt(2)),
        "ranges" -> Nil
      ))
  }

  /** The SD-card controller, if the design has one ([[HasSDCardPMOD]]); constructing it
   * binds its device-tree resources and claims the next INTC input. */
  protected val sdFeature: Option[SdCardFeature] =
    SdCardFeature.ifPresent(mmioBusDevice.get, intcDev, irqs)

  /** The DisplayPort video pipeline, if the design has one ([[HasVideoStream]]);
   * constructing it binds its device-tree resources (VDMA, timing controller, status
   * GPIO, the framebuffer carve-out and - on coherent designs - the /chosen
   * framebuffer node) and claims the next INTC input. */
  protected val videoFeature: Option[VideoStreamFeature] =
    VideoStreamFeature.ifPresent(mmioBusDevice.get, intcDev, irqs, chosenDev, reservedMemoryDev)

  /** True when the board's FPGA carries a Zynq UltraScale+ processing system. Read from the
   * parameters rather than the builder: this trait runs before `bd.init`. */
  private val hasZynqPs: Boolean = p(XilinxFPGAKey).exists(_.isInstanceOf[HasZynqUltraPS])

  /** The window through which PS registers are reached, on boards whose FPGA carries a
   * processing system; constructing it binds its device-tree node (it claims no
   * interrupt). */
  protected val psWindowFeature: Option[PsWindowFeature] =
    PsWindowFeature.ifPresent(hasZynqPs, mmioBusDevice.get)

  /** The PS USB host controller, on boards whose FPGA carries a processing system;
   * constructing it binds its device-tree resources (bus node with `dma-ranges`,
   * restricted DMA pool, controller node) and claims the next INTC input. */
  protected val usbFeature: Option[UsbHostFeature] =
    UsbHostFeature.ifPresent(hasZynqPs, mmioBusDevice.get, intcDev, irqs, reservedMemoryDev)

  /** All present features. Seq order = INTC input claim order; the wiring order differs
   * (see `wireFeatureMains`) because SmartConnect ports and reset slices are allocated
   * there in their own historical sequence. */
  protected val features: Seq[VivadoFeature] =
    Seq(uartFeature, sdFeature, videoFeature, psWindowFeature, usbFeature).flatten

  // Feature order sanity: the interrupt indices handed out during construction must
  // equal the features' declaration order - a reorder above would silently renumber
  // interrupts in both the device tree and the hardware. Fails elaboration instead.
  locally {
    val claimed = features.flatMap(_.claimedIrqs)
    require(claimed.map(_.index) == claimed.indices,
      s"features order no longer matches INTC input order: ${irqs.claims}")
  }

  // Boot arguments: the console selection and the early console describe THIS design's UART,
  // so they belong in the device tree the design emits - not baked into a kernel binary, which
  // would tie the kernel image to one hardware generation. Only bound when the design has a
  // UART to talk through. On a design with a framebuffer console (any video variant, see
  // [[VideoStreamFeature]]), `console=tty0` comes FIRST: every console= entry receives kernel
  // messages, but the LAST one becomes /dev/console - the serial shell must stay primary,
  // with the monitor as a mirror (its own shell runs on tty1, see the shell image's init).
  // Bound after the features: it constructs no device-tree node, only a property value,
  // so its position carries no ordering weight.
  uartFeature.foreach { u =>
    ResourceBinding {
      Resource(chosenDev, "bootargs").bind(ResourceString(
        (if (videoFeature.isDefined) "console=tty0 " else "") +
          s"console=ttyUL0,${u.baud} earlycon=uartlite,mmio,0x${u.base.toHexString}"))
    }
  }

  /**
   * The INTC's own device-tree resources, bound after every peripheral has claimed its
   * input (the input count and edge mask are final only then): the register region and
   * the single level line into the PLIC. None when no device raises interrupts - the
   * design then has no INTC at all and the core's external interrupt is tied off.
   */
  protected val intcDTSOpt: Option[DTSInfo] = if (irqs.count > 0) {
    val dts = DTSInfo(
      parent = mmioBusDevice.get,
      regs = Seq(("reg", VivadoMmioMap.IntcBase, VivadoMmioMap.RegionSize)),
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
      regs = Seq(("reg", VivadoMmioMap.SysResetBase, VivadoMmioMap.RegionSize)),
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
