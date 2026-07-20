package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands, VivadoDesignException}
import soct.system.vivado.abstracts._
import soct.system.vivado.misc.DTSInfo

/**
 * Xilinx AXI Interrupt Controller: collects the fabric peripheral interrupts and cascades
 * them into the PLIC as a single level interrupt.
 *
 * It exists as an impedance matcher: the PLIC's per-source gateways assume LEVEL sources
 * (a source is masked between claim and complete; anything it signals in that window is
 * dropped, on the contract that a level source still asserts afterwards). Peripherals with
 * edge/pulse interrupts - the AXI UART Lite fires a one-clock pulse per FIFO *transition* -
 * break that contract: one pulse lost in the claim window can never recur, wedging the
 * device forever (hardware-diagnosed on the UART RX path). The INTC latches edges per
 * input (`C_KIND_OF_INTR`) and holds its `irq` output high while any enabled interrupt is
 * pending - a proper level the PLIC cannot lose. Linux drives it through the mainline
 * chained-irqchip driver (`CONFIG_XILINX_INTC`, compatible `xlnx,xps-intc-1.00.a`).
 *
 * Note for bare metal: the INTC resets with its master enable off, so nothing reaches the
 * PLIC until software programs it (soctglue and the boot ROMs poll and are unaffected).
 *
 * @param dtsInfo         device-tree description (register region; the node's interrupt
 *                        properties are bound by the system, which knows the input map)
 * @param getAxiMasterPin the AXI master reaching the control registers (the Rocket MMIO port)
 * @param nInputs         number of interrupt inputs (the concat width)
 * @param edgeMask        bit i set = input i is an edge/pulse source to latch; clear = level
 *                        (same encoding as the IP's C_KIND_OF_INTR and the device tree's
 *                        xlnx,kind-of-intr)
 */
case class AXIIntc(override val dtsInfo: DTSInfo, override val getAxiMasterPin: BdIntfPin,
                   nInputs: Int, edgeMask: Int)
                  (implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip with ConnectOps with HasAxiSlave with HasDTSInfo {

  if (nInputs < 1 || nInputs > 32) {
    throw VivadoDesignException(s"AXIIntc supports 1..32 interrupt inputs, got $nInputs")
  }
  if ((edgeMask >> nInputs) != 0) {
    throw VivadoDesignException(
      f"AXIIntc edge mask 0x$edgeMask%x names inputs beyond the $nInputs configured ones")
  }

  override def partName: String = "xilinx.com:ip:axi_intc:4.1"

  // C_NUM_INTR_INPUTS is deliberately absent: Vivado derives it (read-only) from the
  // width of the vector connected to `intr`, i.e. from the interrupt concat sized by the
  // same irqIdx as [[nInputs]] - verified by readback on the built block design. Setting
  // it anyway raises critical warning BD 41-737.
  override def defaultProperties: Map[String, String] = Map(
    // 1 = edge (latched on the rising edge), 0 = level - per input.
    "CONFIG.C_KIND_OF_INTR" -> f"0x$edgeMask%08X",
    // All inputs rising-edge / active-high (the defaults, pinned because correctness
    // depends on them).
    "CONFIG.C_KIND_OF_EDGE" -> "0xFFFFFFFF",
    "CONFIG.C_KIND_OF_LVL" -> "0xFFFFFFFF",
    // The output to the PLIC must be a LEVEL (the whole point of this component).
    "CONFIG.C_IRQ_IS_LEVEL" -> "1",
    "CONFIG.C_IRQ_ACTIVE" -> "1"
  )

  /** The concatenated peripheral interrupts (width [[nInputs]]). */
  object INTR extends BdPinIn("intr", AXIIntc.this)

  /** The single level interrupt towards the PLIC. */
  object IRQ extends BdPinOut("irq", AXIIntc.this)

  object S_AXI_ACLK extends BdPinIn("s_axi_aclk", AXIIntc.this)

  object S_AXI_ARESETN extends BdPinIn("s_axi_aresetn", AXIIntc.this)

  override lazy val S_AXI: BdIntfPin = new BdIntfPin("s_axi", this)

  /**
   * @throws soct.system.vivado.VivadoDesignException if the DTS info does not carry exactly one register region
   */
  override def assignAddrTcl: TCLCommands = {
    val regs = dtsInfo.regs
    if (regs.size != 1) {
      throw VivadoDesignException(s"AXIIntc requires exactly one register region in DTS info, but found ${regs.size}")
    }
    val (_, _offset, _size) = regs.head
    val offset = "0x%08X".format(_offset)
    val size = "0x%08X".format(_size)
    Seq(
      s"assign_bd_address -offset $offset -range $size -target_address_space [get_bd_addr_spaces ${getAxiMasterPin.ref}] [get_bd_addr_segs ${S_AXI.ref}/Reg]".tcl
    )
  }
}
