package soct.system.vivado.features

import freechips.rocketchip.resources.{Device, ResourceInt}
import org.chipsalliance.cde.config.Parameters
import soct.HasAxiUartLite
import soct.vivado.abstracts.BdPinPort.portToBdPin
import soct.vivado.abstracts.{BdIntfPin, BdPinOut}
import soct.vivado.components.AXIUartLite
import soct.vivado.fpga.FPGA
import soct.vivado.misc.{AxiSlaveBinder, DTSInfo, Irq}
import soct.vivado.{SOCTBdBuilder, VivadoDesignException}
import soct.system.vivado.CommonDesign

/**
 * The AXI UART Lite console ([[HasAxiUartLite]]): the design's primary console, on the MMIO
 * path at [[VivadoMmioMap.UartBase]]. [[base]] and [[baud]] are the single source for
 * the DTS `reg` and `current-speed`, the `/chosen` boot arguments (bound by the system)
 * and the IP's C_BAUDRATE (passed to [[soct.vivado.components.AXIUartLite]]).
 */
class UartFeature(mmioBus: Device, intcDev: Device, irqs: IrqAllocator)
                 (implicit p: Parameters, bd: SOCTBdBuilder) extends VivadoFeature {
  override def name: String = "uart"

  val base: Long = VivadoMmioMap.UartBase
  val baud: Int = UartFeature.Baud

  // The UART Lite interrupt is a one-clock PULSE per FIFO transition (PG142) - the
  // INTC must latch it as an edge or it is lost (hardware-diagnosed console wedge).
  private val irq = Irq(intcDev, irqs.claim(name, edge = true))

  val dts: DTSInfo = DTSInfo(
    parent = mmioBus,
    regs = Seq(("reg", base, VivadoMmioMap.RegionSize)),
    irqs = Seq(irq),
    // The soct compatible first (soctglue matches it); the Xilinx one second, so
    // Linux's uartlite driver (SERIAL_UARTLITE) binds the console to this UART.
    compatibles = Seq("riscv,axi-uart-1.0", "xlnx,xps-uartlite-1.00.a"),
    // current-speed is REQUIRED by the Linux uartlite driver (the baud is fixed at
    // synthesis, so the driver refuses to guess): without it the probe fails with
    // -EINVAL and the console never comes up.
    extraProps = Map(
      "port-number" -> Seq(ResourceInt(0)),
      "current-speed" -> Seq(ResourceInt(baud))
    )
  )
  AxiSlaveBinder.bindSimpleDevice(devname = "uart0", dts = dts, perms = AxiSlaveBinder.mmioPerms)

  override def claimedIrqs: Seq[Irq] = Seq(irq)

  private var uart: Option[AXIUartLite] = None

  override def createComponents(fpga: FPGA, axiMMIO: BdIntfPin): Unit = {
    if (fpga.uartPorts.isEmpty) {
      throw new VivadoDesignException(s"FPGA ${fpga.friendlyName} does not have any UART ports defined, but HasAxiUartLite is set to true in parameters.")
    }
    val uartParams = fpga.uartPorts.head
    val port = uartParams.initPort
    uart = Some(AXIUartLite(dts, axiMMIO, port, uartParams, baud = baud))
  }

  override def wirePeripheryFabric(peripheryClock: BdPinOut, c: CommonDesign): Unit =
    uart.foreach { u =>
      peripheryClock --> u.S_AXI_ACLK
      c.periphPsr.PeripheralAResetN --> u.S_AXI_ARESETN
    }

  override def wireIrq(c: CommonDesign): Unit =
    uart.foreach(u => u.INTERRUPT --> c.interruptConcat.IN(irq.index))

  override def wireMmio(c: CommonDesign): Unit =
    uart.foreach(u => c.mmioSMC.M_AXI.next() <-> u.S_AXI)
}

/** Presence decision and synthesis-time constants of [[UartFeature]]. */
object UartFeature {
  /** Fixed at synthesis; flows into the IP's C_BAUDRATE, the device tree and the boot arguments. */
  val Baud: Int = 115200

  /** The single presence decision: `Some` iff the design has a UART ([[HasAxiUartLite]]). */
  def ifPresent(mmioBus: Device, intcDev: Device, irqs: IrqAllocator)
               (implicit p: Parameters, bd: SOCTBdBuilder): Option[UartFeature] =
    if (p(HasAxiUartLite)) Some(new UartFeature(mmioBus, intcDev, irqs)) else None
}
