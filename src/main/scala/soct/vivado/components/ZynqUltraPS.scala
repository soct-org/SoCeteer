package soct.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.HasVideoStream
import soct.vivado.abstracts._
import soct.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands}

/**
 * Zynq UltraScale+ MPSoC processing system, used purely as a peripheral of the PL design:
 * the board preset configures it (clocks, DDR-less, DisplayPort on the PS-GTR lanes, the USB
 * ULPI PHY on MIO), and the design uses these faces of it:
 *  - the DisplayPort controller's live video input (`dp_live_video_in_*`), fed with pixels
 *    from the PL,
 *  - the `S_AXI_LPD` slave port, through which the RISC-V programs PS registers (via an
 *    [[AxiAddrOffset]] window - the PS addresses are fixed),
 *  - the `M_AXI_HPM0_LPD` master port, through which a PS DMA engine reaches PL-side DRAM,
 *    and
 *  - `ps_pl_irq_usb3_0_endpoint`, the USB host controller's interrupt, routed into the PL
 *    interrupt controller.
 *
 * The APU cores are not used; no PS software runs. Everything the PS needs in order to
 * respond at all - PLLs, MIO, SERDES - is applied once per power-up by the `psu_init.tcl`
 * that Vivado generates with the design.
 */
case class ZynqUltraPS()(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip with ConnectOps {

  override def partName: String = "xilinx.com:ip:zynq_ultra_ps_e:3.5"

  /**
   * Create the cell and immediately apply the board preset (clocking, MIO, the DP lane/refclk
   * setup from the board files). Emitted as one multi-line command so both statements stay
   * together when the instantiate commands are sorted; the preset lands before the property
   * phase, so [[defaultProperties]] can override individual preset values.
   */
  override def instTcl: TCLCommands = Seq(
    // Created at bdPath (hierarchy-aware); the automation targets the cell through the
    // TCL variable, so it needs no path at all.
    s"""set $instanceName [create_bd_cell -type ip -vlnv $partName $bdPath]
       |apply_bd_automation -rule xilinx.com:bd_rule:zynq_ultra_ps_e -config {apply_board_preset "1"} $$$instanceName""".stripMargin.tcl
  )

  override def defaultProperties: Map[String, String] = Map(
    // DP live video input from the PL (dp_live_video_in_* pins + dp_video_in_clk). Only when
    // the design actually drives it: the pins it adds have no other source, and Vivado rejects
    // a design whose dp_video_in_clk reaches no clock.
    "CONFIG.PSU__USE__VIDEO" -> (if (p(HasVideoStream).isDefined) "1" else "0"),
    // S_AXI_LPD (= SAXIGP6): PL master access into the PS register space
    "CONFIG.PSU__USE__S_AXI_GP6" -> "1",
    "CONFIG.PSU__SAXIGP6__DATA_WIDTH" -> "32",
    // M_AXI_HPM0_LPD (= M_AXI_GP2): PS master access into the PL, so that a PS DMA engine
    // can reach DRAM. The window it decodes to starts at [[ZynqUltraPS.HpmLpdBase]].
    "CONFIG.PSU__USE__M_AXI_GP2" -> "1",
    // The USB host controller's interrupt, as a PL-facing output pin. Without it the only
    // consumer of that interrupt is the PS GIC, which nothing in this design services.
    //
    // The ENDPOINT interrupt, not the OTG one: the host controller raises its event-ring
    // interrupt on the former, and the latter reports role changes. Xilinx's own device tree
    // names the same line "host" for this controller. Exporting the wrong one produces a design
    // in which the controller initializes and then goes silent, because port changes are
    // announced on the line nothing is listening to.
    "CONFIG.PSU__IRQ_P2F_USB3_ENDPOINT__INT0" -> "1",
    // The board preset enables PS<->PL ports this design does not use; disable them so they
    // do not sit unclocked/unconnected (M_AXI_HPM0/1_FPD = M_AXI_GP0/1, S_AXI_HP0_FPD = S_AXI_GP2).
    "CONFIG.PSU__USE__M_AXI_GP0" -> "0",
    "CONFIG.PSU__USE__M_AXI_GP1" -> "0",
    "CONFIG.PSU__USE__S_AXI_GP2" -> "0"
  )

  /** PL master access into the PS (fixed PS address map, see [[AxiAddrOffset]]) */
  object S_AXI_LPD extends BdIntfPin("S_AXI_LPD", ZynqUltraPS.this)

  object SAXI_LPD_ACLK extends BdPinIn("saxi_lpd_aclk", ZynqUltraPS.this)

  /** PS master access into the PL, used by the PS USB controller's DMA to reach DRAM. */
  object M_AXI_HPM0_LPD extends BdIntfPin("M_AXI_HPM0_LPD", ZynqUltraPS.this)

  object MAXI_HPM0_LPD_ACLK extends BdPinIn("maxihpm0_lpd_aclk", ZynqUltraPS.this)

  /** The USB host controller's interrupt - the line its xHCI event ring raises. */
  object PS_PL_IRQ_USB3_0_HOST extends BdPinOut("ps_pl_irq_usb3_0_endpoint", ZynqUltraPS.this)

  /** Pixel clock of the live video input */
  object DP_VIDEO_IN_CLK extends BdPinIn("dp_video_in_clk", ZynqUltraPS.this)

  /** Live video pixel data, 36 bit (12 bit per component) */
  object DP_LIVE_VIDEO_IN_PIXEL1 extends BdPinIn("dp_live_video_in_pixel1", ZynqUltraPS.this)

  object DP_LIVE_VIDEO_IN_VSYNC extends BdPinIn("dp_live_video_in_vsync", ZynqUltraPS.this)

  object DP_LIVE_VIDEO_IN_HSYNC extends BdPinIn("dp_live_video_in_hsync", ZynqUltraPS.this)

  object DP_LIVE_VIDEO_IN_DE extends BdPinIn("dp_live_video_in_de", ZynqUltraPS.this)
}

object ZynqUltraPS {
  /**
   * Base of the `M_AXI_HPM0_LPD` window in the PS address map. A PS master issuing an address
   * here leaves the processing system through that port and is decoded by the PL.
   *
   * Our DRAM starts at the same address, so mapping the port straight onto memory makes the
   * window an identity map over DRAM's first [[HpmLpdSize]] - a PS DMA engine and the RISC-V
   * then name the same byte by the same address, and no translation is needed anywhere.
   */
  val HpmLpdBase: BigInt = 0x80000000L

  /** Size of the `M_AXI_HPM0_LPD` window, and so the reach of any PS DMA into DRAM. */
  val HpmLpdSize: BigInt = 0x20000000L

  /**
   * Base of the PS register region reachable through [[AxiAddrOffset]], and its size. Chosen so
   * the window covers every PS peripheral this design programs - the DisplayPort controller at
   * 0xFD4A_0000, the USB host controller at 0xFE20_0000 and its wrapper at 0xFF9D_0000 - up to
   * the top of the address space. The size must be a power of two and both bases must be
   * aligned to it (see [[AxiAddrOffset]]).
   */
  val PsWindowTargetBase: BigInt = 0xFC000000L

  val PsWindowSize: BigInt = 0x4000000L

  /**
   * Where that region appears in the Rocket MMIO port's address space. Sits at the very top of
   * the MMIO decode range, directly below DRAM.
   */
  val PsWindowBase: BigInt = 0x7C000000L

  /**
   * The USB host controller's register region in the PS address map: the Synopsys DWC3 core,
   * whose first page is the xHCI register set the host driver drives.
   */
  val UsbCoreBase: BigInt = 0xFE200000L

  val UsbCoreSize: BigInt = 0x40000L

  /**
   * The USB controller's private DMA bounce pool (a `restricted-dma-pool` reserved-memory
   * region), placed at the very top of the [[HpmLpdSize]] window.
   *
   * The controller reaches only DRAM's first [[HpmLpdSize]]; on a board with more memory, most
   * buffers - and the kernel's default bounce pool itself, which is placed high - lie beyond
   * its reach, so ordinary bouncing fails with every slot free. A pool reserved inside the
   * window and named by the usb node's `memory-region` gives the kernel somewhere it can
   * always bounce to. 16 MiB is far more than the controller's 60 MB/s can keep in flight.
   */
  val UsbDmaPoolSize: BigInt = 0x1000000L

  val UsbDmaPoolBase: BigInt = HpmLpdBase + HpmLpdSize - UsbDmaPoolSize

  /**
   * The operating-system console's scanout framebuffer, reserved directly below the USB DMA
   * pool. The `soct-dp` kernel module (`binaries/linux/drivers/dp`) parks the VDMA on this
   * fixed address; the kernel renders its console into the same memory through a
   * `simple-framebuffer` node, with no video driver involved. It must lie in DRAM's
   * 32-bit-addressable first 2 GiB (the VDMA's read master is 32-bit); 8 MiB holds a
   * 1920x1080 24-bit frame (6.2 MiB) with room to spare.
   */
  val VideoFbSize: BigInt = 0x800000L

  val VideoFbBase: BigInt = UsbDmaPoolBase - VideoFbSize
}
