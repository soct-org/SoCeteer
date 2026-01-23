package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.components.ProcSysReset._

case class ProcSysReset()(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain])
  extends InstantiableBdComp with IsXilinxIP {

  override def partName: String = "xilinx.com:ip:proc_sys_reset:5.0"

  override def clockInPorts: Seq[String] = Seq(s"$instanceName/$slowestSyncClk")

  override def resetInPorts: Seq[String] = Seq(s"$instanceName/$extReset")

  /**
   * Use this reset to connect to peripherals needing an active-low / negative polarity reset.
   * Deassertion is synchronized to the slowestSyncClk.
   */
  object PeripheralAResetN extends Reset {}


  // TODO add more reset outputs


  override def connectTclCommands: Seq[String] = {
    val parn = PeripheralAResetN.receiverPorts.flatMap(_._2()).map { port =>
      s"connect_bd_net [get_bd_ports $instanceName/$peripheralAReset] [get_bd_pins $port]"
    }.toSeq

    parn
  }



}


object ProcSysReset {
  val peripheralAReset = "peripheral_aresetn"
  val slowestSyncClk = "slowest_sync_clk"
  val extReset = "ext_reset_in"
}