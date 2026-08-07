package soct.system.vivado.features

import freechips.rocketchip.resources.{Device, ResourceInt}
import org.chipsalliance.cde.config.Parameters
import soct.vivado.abstracts.BdPinPort.portToBdPin
import soct.vivado.components.{SDCardPMOD, SDIOCDPort, SDIOClkPort, SDIOCmdPort, SDIODataPort}
import soct.vivado.misc.{AxiSlaveBinder, DTSInfo, Irq}
import soct.vivado.{SOCTBdBuilder, StringToTCLCommand}
import soct.{HasSDCardPMOD, PeripheryClockDomain}

/**
 * The SD-card controller on a PMOD ([[HasSDCardPMOD]] carries the PMOD port index):
 * control registers on the MMIO path at [[VivadoMmioMap.SdBase]], sector DMA through
 * the coherent DMA path, one level interrupt, and the SDIO I/O timing constraints.
 */
class SdCardFeature(pmodPort: Int, mmioBus: Device, intcDev: Device, irqs: IrqAllocator)
                   (implicit p: Parameters, bd: SOCTBdBuilder) extends VivadoFeature {
  override def name: String = "sd"

  private val irq = Irq(intcDev, irqs.claim(name, edge = false))

  // The controller divides the periphery clock, so the DTS must carry the ACTUAL
  // frequency (the driver derives every SD rate from it) - not a hardcoded value that
  // silently goes stale when the domain is reconfigured. The fastest reachable SD clock
  // is clock/2 (minimum divider), which is also what the driver would derive on its
  // own; anything higher underflows its divider computation.
  private val periphHz = p(PeripheryClockDomain).freq.toHz.toLong

  val dts: DTSInfo = DTSInfo(
    parent = mmioBus,
    regs = Seq(("reg", VivadoMmioMap.SdBase, VivadoMmioMap.RegionSize)),
    irqs = Seq(irq),
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
  AxiSlaveBinder.bindSimpleDevice(devname = "mmc0", dts = dts, perms = AxiSlaveBinder.mmioPerms)

  override def claimedIrqs: Seq[Irq] = Seq(irq)

  override def wireMain(ctx: FeatureWireContext): Unit = {
    val c = ctx.c
    val sdPmod = SDCardPMOD(dtsInfo = dts, getAxiMasterPin = c.axiMMIO,
      getAxiSlavePins = Seq((c.axiDMA, "reg0")))

    val (sdioCd, sdioClk, sdioCmd, sdioData) = (SDIOCDPort(pmodPort), SDIOClkPort(pmodPort), SDIOCmdPort(pmodPort), SDIODataPort(pmodPort))
    val ports = Seq(sdioCd, sdioClk, sdioCmd, sdioData)

    ctx.peripheryClock --> sdPmod.CLOCK
    c.periphPsr.PeripheralAResetN --> sdPmod.ASYNC_RESETN

    sdPmod <-> ports

    c.dmaSMC.S_AXI.next() <-> sdPmod.M_AXI
    c.mmioSMC.M_AXI.next() <-> sdPmod.S_AXI

    sdPmod.INTERRUPT --> c.interruptConcat.IN(irq.index)

    bd.addTimingConstraints(() => Seq(
      s"""# Timing constraints for SDCardPMOD (${sdPmod.bdPath})
         |set sdio_clock [get_clocks -of_objects [get_pins -hier -filter {NAME =~ *${sdPmod.CLOCK.ref}}]]
         |
         |set_max_delay -from $$sdio_clock -to [get_ports {${sdioClk.portName} ${sdioCmd.portName} ${sdioData.portName}*}] -datapath_only 8.0
         |set_max_delay -from [get_ports {${sdioCmd.portName} ${sdioData.portName}*}] -to $$sdio_clock -datapath_only 8.0
         |set_min_delay -from [get_ports {${sdioCd.portName} ${sdioCmd.portName} ${sdioData.portName}*}] -to $$sdio_clock 0.0
         |
         |set_max_delay -from [get_ports ${sdioCd.portName}] -to $$sdio_clock -datapath_only 100.0
         |set_max_delay -from $$sdio_clock -through [get_pins -hier -filter {NAME =~ *${sdPmod.INTERRUPT.ref}}] -datapath_only 10.0
         |""".stripMargin.tcl
    ))
  }
}

/** Presence decision of [[SdCardFeature]]. */
object SdCardFeature {
  /** The single presence decision: `Some` iff the design has an SD PMOD ([[HasSDCardPMOD]]). */
  def ifPresent(mmioBus: Device, intcDev: Device, irqs: IrqAllocator)
               (implicit p: Parameters, bd: SOCTBdBuilder): Option[SdCardFeature] =
    p(HasSDCardPMOD).map(idx => new SdCardFeature(idx, mmioBus, intcDev, irqs))
}
