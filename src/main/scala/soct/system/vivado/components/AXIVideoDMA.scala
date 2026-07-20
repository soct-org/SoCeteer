package soct.system.vivado.components

import freechips.rocketchip.subsystem.ExtMem
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.abstracts._
import soct.system.vivado.misc.DTSInfo
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands, VivadoDesignException}

/**
 * Xilinx AXI Video DMA in read-only (MM2S) configuration: fetches frames from memory and
 * streams them out as AXI4-Stream video. Control registers via AXI4-Lite; the frame reads go
 * through the design's DMA path so they hit DRAM coherently.
 * Documentation: https://docs.amd.com/r/en-US/pg020_axi_vdma
 *
 * @param dtsInfo         device-tree description (control registers, interrupt)
 * @param getAxiMasterPin the AXI master reaching the control registers (the Rocket MMIO port)
 * @param getAxiSlavePins the slaves the frame-read master must reach, with their segment names
 *                        (the Rocket L2 frontend)
 */
case class AXIVideoDMA(override val dtsInfo: DTSInfo, override val getAxiMasterPin: BdIntfPin,
                       override val getAxiSlavePins: Seq[(BdIntfPin, String)])
                      (implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip with ConnectOps with HasAxiSlave with HasAxiMaster with HasDTSInfo {

  override def partName: String = "xilinx.com:ip:axi_vdma:6.3"

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.c_include_s2mm" -> "0", // read-only: no stream-to-memory channel
    "CONFIG.c_include_mm2s" -> "1",
    // Pinned because the device tree advertises them (xlnx,num-fstores and the channel's
    // xlnx,datawidth): hardware and DTS must not drift apart.
    "CONFIG.c_num_fstores" -> s"${AXIVideoDMA.FrameStores}",
    "CONFIG.c_m_axi_mm2s_data_width" -> s"${AXIVideoDMA.MmDataWidth}",
    "CONFIG.c_m_axis_mm2s_tdata_width" -> "24", // RGB888 stream towards the video out
    "CONFIG.c_use_mm2s_fsync" -> "0", // free-running; the video out aligns to the timing generator
    "CONFIG.c_mm2s_genlock_mode" -> "0",
    // Long bursts and a deep line buffer amortize the read latency of the coherent DMA
    // path (SmartConnect -> L2 frontend): the stream must sustain the full pixel rate
    // during active lines.
    "CONFIG.c_mm2s_max_burst_length" -> "256",
    "CONFIG.c_mm2s_linebuffer_depth" -> "4096"
    // The stream side runs on the pixel clock while lite/memory stay on the periphery
    // clock; the IP's independent-clocks parameter (c_prmry_is_aclk_async) is read-only
    // and derived by Vivado from those clock connections.
  )

  /** Control registers (AXI4-Lite) */
  override lazy val S_AXI: BdIntfPin = new BdIntfPin("S_AXI_LITE", this)

  /** Frame reads from memory */
  override lazy val M_AXI: BdIntfPin = new BdIntfPin("M_AXI_MM2S", this)

  /** Pixel stream towards the video out */
  object M_AXIS_MM2S extends BdIntfPin("M_AXIS_MM2S", AXIVideoDMA.this)

  object S_AXI_LITE_ACLK extends BdPinIn("s_axi_lite_aclk", AXIVideoDMA.this)

  object M_AXI_MM2S_ACLK extends BdPinIn("m_axi_mm2s_aclk", AXIVideoDMA.this)

  object M_AXIS_MM2S_ACLK extends BdPinIn("m_axis_mm2s_aclk", AXIVideoDMA.this)

  object AXI_RESETN extends BdPinIn("axi_resetn", AXIVideoDMA.this)

  /** Frame-transfer interrupt (PLIC) */
  object MM2S_INTROUT extends BdPinOut("mm2s_introut", AXIVideoDMA.this)

  private def dmaMasterRange: Long = {
    val extMem = p(ExtMem).get.master
    val dramEnd = extMem.base.toLong + extMem.size.toLong
    var range = java.lang.Long.highestOneBit(dramEnd)
    if (range < dramEnd) range <<= 1
    // The VDMA stays at its default 32-bit addressing: widening c_addr_width makes Vivado
    // exclude the >4G segment again when the saved BD is reloaded (the space is momentarily
    // 32-bit during load and the exclusion sticks). DRAM's first 2 GiB (0x8000_0000..
    // 0xFFFF_FFFF) is 32-bit-addressable, so framebuffers must be allocated there - a driver
    // constraint, not a hardware limitation of the path.
    math.min(range, 0x100000000L)
  }

  /**
   * @throws soct.system.vivado.VivadoDesignException if the DTS info does not carry exactly one register region
   */
  override def assignAddrTcl: TCLCommands = {
    val regs = dtsInfo.regs
    if (regs.size != 1) {
      throw VivadoDesignException(s"AXIVideoDMA DTSInfo must have exactly one reg entry, but found ${regs.size}")
    }
    val (_, _offset, _size) = regs.head
    val offset = "0x%08X".format(_offset)
    val range = "0x%08X".format(_size)
    val slaveConnects = Seq(
      s"assign_bd_address -offset $offset -range $range -target_address_space [get_bd_addr_spaces ${getAxiMasterPin.ref}] [get_bd_addr_segs ${S_AXI.ref}/Reg]".tcl
    )

    // The VDMA names its read master's address space "Data_MM2S" (not after the interface pin).
    val mm2sSpace = s"$instanceName/Data_MM2S"
    val masterConnects = getAxiSlavePins.map { case (pin, regName) =>
      s"""assign_bd_address -offset 0x00000000 -range 0x${dmaMasterRange.toHexString.toUpperCase} -target_address_space [get_bd_addr_spaces $mm2sSpace] [get_bd_addr_segs ${pin.ref}/$regName]
         |# The exported bus segment is tagged 'register' usage while Data_MM2S expects 'memory',
         |# so Vivado excludes it as a precaution (BD 41-1051) - re-include it, which is the
         |# override the warning itself prescribes.
         |include_bd_addr_seg [get_bd_addr_segs -excluded -of_objects [get_bd_addr_spaces $mm2sSpace]]""".stripMargin.tcl
    }

    masterConnects ++ slaveConnects
  }
}

object AXIVideoDMA {
  /** Frame-store count (`c_num_fstores`): three, the classic frame-buffer triple, giving
   * a Linux driver tear-free page flipping without over-provisioning registers. The DTS
   * `xlnx,num-fstores` advertises the same value. */
  val FrameStores = 3

  /** Memory-map data width of the MM2S frame-read master in bits
   * (`c_m_axi_mm2s_data_width`, the IP default; the SmartConnect upconverts to the
   * 64-bit DMA path). The DTS channel node's `xlnx,datawidth` must match. */
  val MmDataWidth = 32
}
