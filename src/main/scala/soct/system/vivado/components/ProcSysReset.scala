package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.components.ProcSysReset._

/**
 * Proc Sys Reset IP core from Xilinx
 * @param dom Only used for the slowestSyncClk connection
 */
case class ProcSysReset()(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain])
  extends InstantiableBdComp with IsXilinxIP with ReceivesClock with SourceForPins with ProvidesAutoReset {

  override def partName: String = "xilinx.com:ip:proc_sys_reset:5.0"

  override def clockInPorts: Seq[BdPinBase] = Seq(BdPin(slowestSyncClk, this))

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


  /**
   * The reset providers provided by this component - Ensure proper ordering if multiple resets are present!
   */
  override val resets: Seq[ResetType] = Seq(
    PeripheralAResetN,
    PeripheralReset,
    BusStructReset,
    InterconnectResetN
  )

  override def connectTclCommands: Seq[String] = resetTclCommands

  /**
   * Implementation method to get the reset output port for a given reset type and sink pin
   *
   * @param reset    The reset type
   * @param resetIdx The index of the reset in the resets sequence
   * @param sinkPin  The sink pin to connect to
   * @param pinIdx   The index of the sink pin in the sinkPins sequence
   * @throws XilinxDesignException if a reset output port cannot be found for a given reset type and sink pin
   * @return The source BdPin to connect to the sink pin
   */
  override protected def resetOutPortImpl(reset: ResetType, resetIdx: Int, sinkPin: BdPinBase, pinIdx: Int): BdPin = {
    reset match {
      case PeripheralAResetN => BdPin(peripheralAReset, this)
      case PeripheralReset => BdPin(peripheralReset, this)
      case BusStructReset => BdPin(busStructReset, this)
      case InterconnectResetN => BdPin(interconnectAResetN, this)
      case _ => throw new Exception(s"Unknown reset type for ProcSysReset: ${reset.getClass.getName}")
    }
  }
}


object ProcSysReset {
  private val peripheralAReset = "peripheral_aresetn"
  private val peripheralReset = "peripheral_reset"
  private val busStructReset = "bus_struct_reset"
  private val interconnectAResetN = "interconnect_aresetn"
  private val slowestSyncClk = "slowest_sync_clk"
  val extReset = "ext_reset_in"
}