package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, TCLCommand, TCLCommands, XilinxDesignException}
import soct.system.vivado.components.ProcSysReset._
import soct.system.vivado.abstracts._
import soct.system.vivado.components.ProcSysReset.Keys._

import scala.collection.mutable

/**
 * Proc Sys Reset IP core from Xilinx.
 * Documentation: https://docs.amd.com/v/u/en-US/pg164-proc-sys-reset
 *
 * @param dom Only used for the slowestSyncClk connection
 */
case class ProcSysReset()(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain])
  extends BdComp with Xip with ReceivesClock with SourceForSinks with ProvidesAutoReset with HasSinkPins with Finalizable {

  override def partName: String = "xilinx.com:ip:proc_sys_reset:5.0"

  override def clockInPorts: () => Seq[BdPinPort] = () => Seq(SlowestSyncClk.getPin(this)())

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

  case class Entry(name: String, max: Int, sliceMap: mutable.Map[Int, InlineSlice])

  private val resetMap: mutable.Map[ResetType, Entry] = mutable.Map(
    PeripheralAResetN -> Entry(peripheralAReset, MAX_PERIPHERAL_ARESETN, mutable.Map[Int, InlineSlice]()),
    PeripheralReset -> Entry(peripheralReset, MAX_PERIPHERAL_RESET, mutable.Map[Int, InlineSlice]()),
    BusStructReset -> Entry(busStructReset, MAX_BUS_STRUCT_RESET, mutable.Map[Int, InlineSlice]()),
    InterconnectResetN -> Entry(interconnectAResetN, MAX_INTERCONNECT_ARESETN, mutable.Map[Int, InlineSlice]())
  )

  require(domains.toSet == resetMap.keySet, "Internal Bug: Reset domains do not match resetMap keys")

  override protected def outPortImpl(reset: ResetType, resetIdx: Int, sinkPin: BdPinPort, pinIdx: Int): BdPinPort = {
    val dinWidth = reset.sinkPins.size
    if (dinWidth <= 1) { // No slicing needed
      return BdPin(resetMap(reset).name, this)
    }
    val sliceCache = resetMap(reset).sliceMap
    val idx = pinIdx % resetMap(reset).max
    val slice = sliceCache.get(idx) match {
      case Some(s) => s
      case None =>
        throw XilinxDesignException(s"Slice for reset $reset idx=$idx not allocated. Components must be finalized before generating connections.")
    }
    slice.output
  }

  // Connect the slices to the main reset output pins
  otherConnects.addOne(() => {
    resetMap.toSeq.flatMap { case (_, entry) =>
      entry.sliceMap.values.toSeq.map { slice =>
        val sink = slice.input
        val source = BdPin(entry.name, this)
        BdPinPort.connect1(source, sink)
      }
    }
  })


  override protected def finalizeBdImpl(): Seq[BdComp] = {
    var slices: mutable.Seq[BdComp] = mutable.Seq.empty

    def newSlice(dinWidth: Int, idx: Int, entry: Entry): InlineSlice = {
      val slice = new InlineSlice(dinWidth, idx, idx, 1) {
        override def instanceName: String = {
          val procSysResetName = ProcSysReset.this.instanceName
          s"${procSysResetName}_${entry.name}_slice_$idx"
        }
      }
      slices :+= slice
      slice
    }

    // For each reset type, create slices as needed
    resetMap.foreach { case (resetType, entry) =>
      val dinWidth = resetType.sinkPins.size
      if (dinWidth > 1) {
        for (idx <- 0 until (dinWidth min entry.max)) {
          val slice = newSlice(dinWidth, idx, entry)
          entry.sliceMap(idx) = slice
        }
      }
    }
    slices.toSeq
  }

  override protected def connectToSinksImpl: TCLCommands = {
    Seq.empty
  }

  override protected def getPinImpl(source: SourceForSinks, sinkKey: KeyForSink): Option[BdPinPort] = {
    sinkKey match {
      case DcmLocked => Some(DcmLocked.getPin(this)())
      case _ => None
    }
  }
}


object ProcSysReset {
  object Keys {
    object SlowestSyncClk extends KeyForSink {
      override def getPin[T <: BdComp](comp: T): () => BdPinPort = () => BdPin("slowest_sync_clk", comp)
    }

    object DcmLocked extends KeyForSink {
      override def getPin[T <: BdComp](comp: T): () => BdPinPort = () => BdPin("dcm_locked", comp)
    }

    object ExtReset extends KeyForSink {
      override def getPin[T <: BdComp](comp: T): () => BdPinPort = () => BdPin("ext_reset_in", comp)
    }
  }

  private val peripheralAReset = "peripheral_aresetn"
  private val MAX_PERIPHERAL_ARESETN = 16

  private val peripheralReset = "peripheral_reset"
  private val MAX_PERIPHERAL_RESET = 16

  private val busStructReset = "bus_struct_reset"
  private val MAX_BUS_STRUCT_RESET = 8

  private val interconnectAResetN = "interconnect_aresetn"
  private val MAX_INTERCONNECT_ARESETN = 8
}