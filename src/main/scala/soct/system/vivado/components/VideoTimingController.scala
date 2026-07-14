package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.abstracts._
import soct.system.vivado.misc.DTSInfo
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands, VivadoDesignException}

/**
 * Xilinx Video Timing Controller in generation mode: produces the sync/blanking timing the
 * video out aligns the pixel stream to. The timing is programmed at runtime through the
 * AXI4-Lite interface (the DisplayPort driver has to program mode timing anyway, so nothing
 * is baked in statically; the IP's power-on default is 1080p).
 * Documentation: https://docs.amd.com/r/en-US/pg016_v_tc
 *
 * @param dtsInfo         device-tree description (control registers)
 * @param getAxiMasterPin the AXI master reaching the control registers (the Rocket MMIO port)
 */
case class VideoTimingController(override val dtsInfo: DTSInfo, override val getAxiMasterPin: BdIntfPin)
                                (implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip with ConnectOps with HasAxiSlave with HasDTSInfo {

  override def partName: String = "xilinx.com:ip:v_tc:6.2"

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.HAS_AXI4_LITE" -> "true",
    "CONFIG.enable_detection" -> "false" // generator only; no incoming video to measure
  )

  /** Control registers (AXI4-Lite; the IP names the interface "ctrl") */
  override lazy val S_AXI: BdIntfPin = new BdIntfPin("ctrl", this)

  /** Generated timing towards the video out */
  object VTIMING_OUT extends BdIntfPin("vtiming_out", VideoTimingController.this)

  /** Pixel clock */
  object CLK extends BdPinIn("clk", VideoTimingController.this)

  object CLKEN extends BdPinIn("clken", VideoTimingController.this)

  /** Generator clock enable - driven by the video out's vtg_ce */
  object GEN_CLKEN extends BdPinIn("gen_clken", VideoTimingController.this)

  /** Active-low core reset (pixel domain) */
  object RESETN extends BdPinIn("resetn", VideoTimingController.this)

  object S_AXI_ACLK extends BdPinIn("s_axi_aclk", VideoTimingController.this)

  object S_AXI_ARESETN extends BdPinIn("s_axi_aresetn", VideoTimingController.this)

  object FSYNC_IN extends BdPinIn("fsync_in", VideoTimingController.this)

  /**
   * @throws soct.system.vivado.VivadoDesignException if the DTS info does not carry exactly one register region
   */
  override def assignAddrTcl: TCLCommands = {
    val regs = dtsInfo.regs
    if (regs.size != 1) {
      throw VivadoDesignException(s"VideoTimingController DTSInfo must have exactly one reg entry, but found ${regs.size}")
    }
    val (_, _offset, _size) = regs.head
    val offset = "0x%08X".format(_offset)
    val size = "0x%08X".format(_size)
    Seq(
      s"assign_bd_address -offset $offset -range $size -target_address_space [get_bd_addr_spaces ${getAxiMasterPin.ref}] [get_bd_addr_segs ${S_AXI.ref}/Reg]".tcl
    )
  }
}
