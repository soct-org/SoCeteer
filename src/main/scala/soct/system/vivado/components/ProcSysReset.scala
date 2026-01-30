package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, TCLCommand, TCLCommands}
import soct.system.vivado.components.ProcSysReset._
import soct.system.vivado.abstracts._

import scala.collection.mutable

/**
 * Proc Sys Reset IP core from Xilinx.
 * Documentation: https://docs.amd.com/v/u/en-US/pg164-proc-sys-reset
 *
 * @param dom Only used for the slowestSyncClk connection
 */
case class ProcSysReset()(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain])
  extends BdComp with Xip with ReceivesClock with SourceForSinks with ProvidesAutoReset {

  override def partName: String = "xilinx.com:ip:proc_sys_reset:5.0"

  override def clockInPorts: () => Seq[BdPinPort] = () => Seq(BdPin(slowestSyncClk, this))

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


  override def defaultProperties: Map[String, String] = {
    val numPeripheralAResetN = PeripheralAResetN.sinkPins.size min MAX_PERIPHERAL_ARESETN max 1
    val numPeripheralReset = PeripheralReset.sinkPins.size min MAX_PERIPHERAL_RESET max 1
    val numBusStructReset = BusStructReset.sinkPins.size min MAX_BUS_STRUCT_RESET max 1
    val numInterconnectResetN = InterconnectResetN.sinkPins.size min MAX_INTERCONNECT_ARESETN max 1

    Map(
      "CONFIG.C_NUM_PERP_ARESETN" -> s"$numPeripheralAResetN",
      "CONFIG.C_NUM_PERP_RST" -> s"$numPeripheralReset",
      "CONFIG.C_NUM_BUS_RST" -> s"$numBusStructReset",
      "CONFIG.C_NUM_INTERCONNECT_ARESETN" -> s"$numInterconnectResetN"
    )
  }

  /**
   * The reset providers provided by this component - Ensure proper ordering if multiple resets are present!
   */
  override val domains: Seq[ResetType] = Seq(
    PeripheralAResetN,
    PeripheralReset,
    BusStructReset,
    InterconnectResetN
  )

  private val sliceMap = Map(
    PeripheralAResetN -> mutable.Map[Int, InlineSlice](),
    PeripheralReset -> mutable.Map[Int, InlineSlice](),
    BusStructReset -> mutable.Map[Int, InlineSlice](),
    InterconnectResetN -> mutable.Map[Int, InlineSlice]()
  )

  private val maxMap = Map(
    PeripheralAResetN -> MAX_PERIPHERAL_ARESETN,
    PeripheralReset -> MAX_PERIPHERAL_RESET,
    BusStructReset -> MAX_BUS_STRUCT_RESET,
    InterconnectResetN -> MAX_INTERCONNECT_ARESETN
  )

  private val nameMap = Map(
    PeripheralAResetN -> peripheralAReset,
    PeripheralReset -> peripheralReset,
    BusStructReset -> busStructReset,
    InterconnectResetN -> interconnectAResetN
  )

  override protected def outPortImpl(reset: ResetType, resetIdx: Int, sinkPin: BdPinPort, pinIdx: Int): BdPinPort = {
    val dinWidth = reset.sinkPins.size
    if (dinWidth <= 1) { // No slicing needed
      return BdPin(nameMap(reset), this)
    }
    val sliceCache = sliceMap(reset)
    val idx = pinIdx % maxMap(reset)
    val slice = sliceCache.getOrElseUpdate(idx,
      new InlineSlice(dinWidth, idx, idx, 1) {override def instanceName: String = {
        val procSysResetName = ProcSysReset.this.instanceName
        s"${procSysResetName}_${nameMap(reset)}_slice_$idx"
      }
      }
    )
    slice.output
  }

  // Connect the slices to the main reset output pins
  otherConnects.addOne(() => {
    sliceMap.toSeq.flatMap { case (reset, sliceCache) =>
      sliceCache.values.toSeq.map { slice =>
        val sink = slice.input
        val source = BdPin(nameMap(reset), this)
        BdPinPort.connect1(source, sink)
      }
    }
  })

  override protected def connectToSinksImpl: TCLCommands = {
    Seq.empty
  }
}


object ProcSysReset {
  private val peripheralAReset = "peripheral_aresetn"
  private val MAX_PERIPHERAL_ARESETN = 16

  private val peripheralReset = "peripheral_reset"
  private val MAX_PERIPHERAL_RESET = 16

  private val busStructReset = "bus_struct_reset"
  private val MAX_BUS_STRUCT_RESET = 8

  private val interconnectAResetN = "interconnect_aresetn"
  private val MAX_INTERCONNECT_ARESETN = 8

  private val slowestSyncClk = "slowest_sync_clk"
  private val dcmLocked = "dcm_locked"
  private val extReset = "ext_reset_in"
}