package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.components.ProcSysReset._

/**
 * Proc Sys Reset IP core from Xilinx
 * @param dom Only used for the slowestSyncClk connection
 */
case class ProcSysReset()(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain] = None)
  extends InstantiableBdComp with IsXilinxIP with ReceivesClock with SourceForPins {

  override def partName: String = "xilinx.com:ip:proc_sys_reset:5.0"

  override def clockInPorts: Seq[BdPinType] = Seq(BdPin(slowestSyncClk, this))

  /**
   * Use this reset to connect to peripherals needing an active-low / negative polarity reset.
   * Deassertion is synchronized to the slowestSyncClk.
   */
  object PeripheralAResetN extends ResetN {}

  /**
   * Use this reset to connect to peripherals needing an active-high / positive polarity reset.
   * Deassertion is synchronized to the slowestSyncClk.
   */
  object PeripheralReset extends Reset {}

  /**
   * Bus Structures reset - for example, arbiters for bridges. Active-High
   */
  object BusStructReset extends Reset {}

  /**
   * Interconnect_aresetn reset, for example, interconnects with active-Low reset inputs.
   */
  object InterconnectResetN extends ResetN {}


  override def connectTclCommands: Seq[String] = {
    val resetPairs = Seq(
      (PeripheralAResetN, peripheralAReset),
      (PeripheralReset, peripheralReset),
      (BusStructReset, busStructReset),
      (InterconnectResetN, interconnectAResetN)
    )

    resetPairs.flatMap { case (resetObj, portName) =>
      resetObj.receiverPorts.flatMap(_._2()).map { port =>
        s"connect_bd_net [get_bd_ports $instanceName/$portName] [get_bd_pins $port]"
      }
    }
  }
}


object ProcSysReset {
  val peripheralAReset = "peripheral_aresetn"
  val peripheralReset = "peripheral_reset"
  val busStructReset = "bus_struct_reset"
  val interconnectAResetN = "interconnect_aresetn"
  val slowestSyncClk = "slowest_sync_clk"
  val extReset = "ext_reset_in"
}