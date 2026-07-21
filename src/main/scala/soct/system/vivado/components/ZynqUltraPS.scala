package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.abstracts._
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands}

/**
 * Zynq UltraScale+ MPSoC processing system, used purely as a peripheral of the PL design:
 * the board preset configures it (clocks, DDR-less, DisplayPort on the PS-GTR lanes), and the
 * design uses exactly two of its faces:
 *  - the DisplayPort controller's live video input (`dp_live_video_in_*`), fed with pixels
 *    from the PL, and
 *  - the `S_AXI_LPD` slave port, through which the RISC-V programs the DP controller's
 *    registers (via an [[AxiAddrOffset]] window - the PS addresses are fixed).
 *
 * The APU cores are not used; no PS software runs.
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
    // DP live video input from the PL (dp_live_video_in_* pins + dp_video_in_clk)
    "CONFIG.PSU__USE__VIDEO" -> "1",
    // S_AXI_LPD (= SAXIGP6): PL master access into the PS register space
    "CONFIG.PSU__USE__S_AXI_GP6" -> "1",
    "CONFIG.PSU__SAXIGP6__DATA_WIDTH" -> "32",
    // The board preset enables PS<->PL ports this design does not use; disable them so they
    // do not sit unclocked/unconnected (M_AXI_HPM0/1_FPD = M_AXI_GP0/1, S_AXI_HP0_FPD = S_AXI_GP2).
    "CONFIG.PSU__USE__M_AXI_GP0" -> "0",
    "CONFIG.PSU__USE__M_AXI_GP1" -> "0",
    "CONFIG.PSU__USE__S_AXI_GP2" -> "0"
  )

  /** PL master access into the PS (fixed PS address map, see [[AxiAddrOffset]]) */
  object S_AXI_LPD extends BdIntfPin("S_AXI_LPD", ZynqUltraPS.this)

  object SAXI_LPD_ACLK extends BdPinIn("saxi_lpd_aclk", ZynqUltraPS.this)

  /** Pixel clock of the live video input */
  object DP_VIDEO_IN_CLK extends BdPinIn("dp_video_in_clk", ZynqUltraPS.this)

  /** Live video pixel data, 36 bit (12 bit per component) */
  object DP_LIVE_VIDEO_IN_PIXEL1 extends BdPinIn("dp_live_video_in_pixel1", ZynqUltraPS.this)

  object DP_LIVE_VIDEO_IN_VSYNC extends BdPinIn("dp_live_video_in_vsync", ZynqUltraPS.this)

  object DP_LIVE_VIDEO_IN_HSYNC extends BdPinIn("dp_live_video_in_hsync", ZynqUltraPS.this)

  object DP_LIVE_VIDEO_IN_DE extends BdPinIn("dp_live_video_in_de", ZynqUltraPS.this)
}
