package soct.system.vivado.features

import freechips.rocketchip.resources.{Device, ResourceInt}
import org.chipsalliance.cde.config.Parameters
import soct.vivado.abstracts.BdPinPort.portToBdPin
import soct.vivado.components.{AxiAddrOffset, ZynqUltraPS}
import soct.vivado.fpga.HasZynqUltraPS
import soct.vivado.misc.{AxiSlaveBinder, DTSInfo, Irq}
import soct.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands}

/**
 * The window through which the RISC-V reaches the PS register space
 * ([[soct.vivado.components.AxiAddrOffset]]). The PS peripherals this design
 * programs - the DisplayPort controller, the USB host controller - live at fixed
 * addresses that the Rocket MMIO port cannot decode directly without overlapping DRAM,
 * so a window of the MMIO space is offset onto them. One window serves all of them:
 * `S_AXI_LPD` is the only way in. The window's addresses ([[ZynqUltraPS.PsWindowBase]]
 * and friends) feed both the device-tree node and the hardware below, so the two
 * cannot disagree.
 */
class PsWindowFeature(mmioBus: Device)
                     (implicit p: Parameters, bd: SOCTBdBuilder) extends VivadoFeature {
  override def name: String = "ps-window"

  /** `soct,ps-base` carries the fixed PS base the window maps to, so software can
   * translate documented PS addresses without magic numbers. */
  val dts: DTSInfo = DTSInfo(
    parent = mmioBus,
    regs = Seq(("reg", ZynqUltraPS.PsWindowBase.toLong, ZynqUltraPS.PsWindowSize.toLong)),
    compatibles = Seq("soct,zynqmp-ps-window"),
    extraProps = Map("soct,ps-base" -> Seq(ResourceInt(ZynqUltraPS.PsWindowTargetBase)))
  )
  AxiSlaveBinder.bindSimpleDevice(devname = "pswin0", dts = dts, perms = AxiSlaveBinder.mmioPerms)

  override def claimedIrqs: Seq[Irq] = Nil

  override def wireMain(ctx: FeatureWireContext): Unit = {
    val c = ctx.c
    val ps = bd.fpgaInstance() match {
      case fpga: HasZynqUltraPS => fpga.getZynqUltraPS()
      case _ => return
    }
    val window = new AxiAddrOffset(
      getAxiMasterPin = c.axiMMIO,
      windowBase = ZynqUltraPS.PsWindowBase,
      windowSize = ZynqUltraPS.PsWindowSize,
      targetBase = ZynqUltraPS.PsWindowTargetBase
    ) {
      override def assignAddrTcl: TCLCommands = {
        // The PS slave segments carry fixed PS addresses; assign them as-is into our master space.
        super.assignAddrTcl ++ Seq(
          s"assign_bd_address -target_address_space [get_bd_addr_spaces ${M_AXI.ref}] [get_bd_addr_segs ${ps.bdPath}/SAXIGP6/*]".tcl
        )
      }
    }.withInstanceName("ps_window")

    ctx.peripheryClock --> Seq(ps.SAXI_LPD_ACLK, window.ACLK)
    c.mmioSMC.M_AXI.next() <-> window.S_AXI
    window.M_AXI <-> ps.S_AXI_LPD
  }
}

object PsWindowFeature {
  /** The single presence decision, passed in as `hasZynqPs`: the system computes it once
   * from the parameters (this runs before `bd.init`, so the builder cannot be asked). */
  def ifPresent(hasZynqPs: Boolean, mmioBus: Device)
               (implicit p: Parameters, bd: SOCTBdBuilder): Option[PsWindowFeature] =
    if (hasZynqPs) Some(new PsWindowFeature(mmioBus)) else None
}
