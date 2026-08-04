package soct.system.vivado.features

import freechips.rocketchip.resources.{Description, Device, Resource, ResourceAddress, ResourceBinding, ResourceBindings, ResourceInt, ResourceReference, ResourceString, SimpleDevice}
import org.chipsalliance.cde.config.Parameters
import soct.vivado.abstracts.BdPinPort.portToBdPin
import soct.vivado.components.ZynqUltraPS
import soct.vivado.fpga.HasZynqUltraPS
import soct.vivado.misc.{AddressSets, AxiSlaveBinder, DTSInfo, Irq}
import soct.vivado.{SOCTBdBuilder, StringToTCLCommand}

/**
 * The PS USB host controller: its DMA master on the coherent DMA path, its interrupt
 * into the PL interrupt controller, and a device tree that binds the bare Synopsys
 * core rather than Xilinx's `xlnx,zynqmp-dwc3` wrapper - that glue driver configures
 * clocks, resets and the PHY through the ZynqMP firmware interface, which is an
 * APU-side service this design does not run. Everything it would do is already done
 * by psu_init, so the core driver can bind directly.
 *
 * The controller, its ULPI PHY and its MIO pins all come from the board preset and are
 * brought up by psu_init, so nothing is built in the fabric - this costs one AXI port
 * and one wire. The DMA lands on the coherent path ([[soct.system.vivado.CommonDesign.dmaSMC]])
 * rather than on the memory controller directly: the RISC-V has no cache-maintenance
 * instructions, so software could not make an incoherent master's writes visible to
 * itself. USB 2.0 high speed peaks at 60 MB/s, well inside what that path sustains.
 */
class UsbHostFeature(mmioBus: Device, intcDev: Device, irqs: IrqAllocator, reservedMemoryDev: Device)
                    (implicit p: Parameters, bd: SOCTBdBuilder) extends VivadoFeature {
  override def name: String = "usb"

  // A bus node interposed purely to carry `dma-ranges`. The controller reaches only the part
  // of DRAM behind its window, and that limit has to be stated where the kernel looks for it:
  // of_dma_configure() starts its search at the device's PARENT, so a `dma-ranges` on the
  // device itself is never read. It cannot go on the shared MMIO bus either - the SD card and
  // the frame DMA sit there too and reach further, and one limit written there would bind all
  // of them to the narrowest.
  private val usbBus = new SimpleDevice("bus", Seq("simple-bus")) {
    override def parent: Some[Device] = Some(mmioBus)
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping ++ Map(
        "#address-cells" -> Seq(ResourceInt(1)),
        "#size-cells" -> Seq(ResourceInt(1)),
        // Empty `ranges`: the child's registers need no translation, only its DMA does.
        "ranges" -> Nil,
        "dma-ranges" -> Seq(ResourceInt(ZynqUltraPS.HpmLpdBase),
          ResourceInt(ZynqUltraPS.HpmLpdBase), ResourceInt(ZynqUltraPS.HpmLpdSize))
      ))
    }
  }

  // The controller's private bounce pool. Its DMA reaches only DRAM's first HpmLpdSize, and
  // on this much memory both the buffers handed to it and the kernel's own default bounce
  // pool (placed high) usually lie beyond that - bouncing then fails with every slot free
  // ("swiotlb buffer is full ... used 0"). A `restricted-dma-pool` reserved inside the
  // window, named by the usb node's `memory-region`, is the mainline mechanism for exactly
  // this: the device gets a bounce pool guaranteed to be where it can reach.
  private val usbDmaPool = new SimpleDevice("restricted-dma-pool", Seq("restricted-dma-pool")) {
    override def parent: Some[Device] = Some(reservedMemoryDev)
  }
  ResourceBinding {
    Resource(usbDmaPool, "reg").bind(ResourceAddress(
      AddressSets.fromOffsetRange(ZynqUltraPS.UsbDmaPoolBase.toLong, ZynqUltraPS.UsbDmaPoolSize.toLong),
      AxiSlaveBinder.mmioPerms))
  }

  private val windowOffset = ZynqUltraPS.PsWindowBase - ZynqUltraPS.PsWindowTargetBase
  private val irq = Irq(intcDev, irqs.claim(name, edge = false))

  /** Registers named at their address in the PS window; `maximum-speed` holds the
   * controller to USB 2.0, which keeps it on the ULPI PHY and off the PS-GTR SERDES -
   * high speed is 480 Mb/s, far above what the DMA path or the devices need. */
  val dts: DTSInfo = DTSInfo(
    parent = usbBus,
    regs = Seq(("reg", (ZynqUltraPS.UsbCoreBase + windowOffset).toLong, ZynqUltraPS.UsbCoreSize.toLong)),
    irqs = Seq(irq),
    compatibles = Seq("snps,dwc3"),
    extraProps = Map(
      "dr_mode" -> Seq(ResourceString("host")),
      "maximum-speed" -> Seq(ResourceString("high-speed")),
      "snps,hsphy_interface" -> Seq(ResourceString("ulpi")),
      // The dwc3 tuning mainline's zynqmp.dtsi ships for this silicon: the frame
      // length adjustment trims the SOF interval the controller times against the
      // ULPI clock, and resuming with HS terminations keeps resume signalling in
      // spec - both matter for device compatibility on this PHY.
      "snps,quirk-frame-length-adjustment" -> Seq(ResourceInt(0x20)),
      "snps,resume-hs-terminations" -> Nil,
      // Named rather than left to position: the driver asks for "host" first and only falls
      // back to the first interrupt, so naming it states which line this is instead of
      // relying on there being exactly one.
      "interrupt-names" -> Seq(ResourceString("host")),
      "memory-region" -> Seq(ResourceReference(usbDmaPool.label))
    )
  )
  AxiSlaveBinder.bindSimpleDevice(devname = "usb", dts = dts, perms = AxiSlaveBinder.mmioPerms)

  override def claimedIrqs: Seq[Irq] = Seq(irq)

  override def wireMain(ctx: FeatureWireContext): Unit = {
    val c = ctx.c
    val ps = bd.fpgaInstance() match {
      case fpga: HasZynqUltraPS => fpga.getZynqUltraPS()
      case _ => return
    }
    ctx.peripheryClock --> ps.MAXI_HPM0_LPD_ACLK
    c.dmaSMC.S_AXI.next() <-> ps.M_AXI_HPM0_LPD

    // The PS masters through its own address map, in which the window out to the PL begins at
    // HpmLpdBase. DRAM begins at the same address, so the mapping is an identity and a PS
    // address and a RISC-V address name the same byte.
    bd.addConfigTcl(() => Seq(
      ("assign_bd_address" +
        s" -offset 0x${ZynqUltraPS.HpmLpdBase.toString(16).toUpperCase}" +
        s" -range 0x${ZynqUltraPS.HpmLpdSize.toString(16).toUpperCase}" +
        s" -target_address_space [get_bd_addr_spaces ${ps.bdPath}/Data]" +
        s" [get_bd_addr_segs ${c.axiDMA.ref}/reg0]").tcl
    ))

    ps.PS_PL_IRQ_USB3_0_HOST --> c.interruptConcat.IN(irq.index)
  }
}

object UsbHostFeature {
  /** The single presence decision, passed in as `hasZynqPs`: the system computes it once
   * from the parameters (this runs before `bd.init`, so the builder cannot be asked). */
  def ifPresent(hasZynqPs: Boolean, mmioBus: Device, intcDev: Device, irqs: IrqAllocator,
                reservedMemoryDev: Device)
               (implicit p: Parameters, bd: SOCTBdBuilder): Option[UsbHostFeature] =
    if (hasZynqPs) Some(new UsbHostFeature(mmioBus, intcDev, irqs, reservedMemoryDev)) else None
}
