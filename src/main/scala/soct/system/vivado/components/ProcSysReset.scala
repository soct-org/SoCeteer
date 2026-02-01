package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, TCLCommands}
import soct.system.vivado.abstracts._

import scala.collection.mutable

/**
 * Proc Sys Reset IP core from Xilinx.
 * Documentation: https://docs.amd.com/v/u/en-US/pg164-proc-sys-reset
 *
 * @param dom Only used for the slowestSyncClk connection
 */
case class ProcSysReset()(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip {

  override def partName: String = "xilinx.com:ip:proc_sys_reset:5.0"

  object SLOWEST_SYNC_CLK extends BdPin("slowest_sync_clk", ProcSysReset.this)

  trait ProcSysResetPort extends Finalizable {
    self: BdPin =>

    val maxOutputs: Int

    def sinks: Seq[BdPinPort] = bd.getSinks(self)

    def dinWidth: Int = sinks.size min maxOutputs max 1 // Every ProcSysReset port has at least one output

    override protected def finalizeBdImpl(): Unit = {
      val idxToSlice = mutable.Map.empty[Int, InlineSlice]
      if (dinWidth < 2) {
        return // No slicing needed
      } else {
        // Remove existing connections from bd as we will rewire after slicing
        bd.removeConnection(this)
      }

      soct.log.debug(s"Slicing $this of ${ProcSysReset.this.instanceName} into $dinWidth slices for ${sinks.size} sinks")
      // Go through each io and calc which slice it goes to (idx % maxOutputs) to have even distribution
      sinks.zipWithIndex.foreach {
        case (sink, i) =>
          val sliceIdx = i % maxOutputs
          val slice = idxToSlice.getOrElseUpdate(sliceIdx,
            new InlineSlice(dinWidth, sliceIdx, sliceIdx, 1) {
              override def instanceName: String = s"${ProcSysReset.this.instanceName}_${pin}_slice_$sliceIdx"
            }
          )
          soct.log.debug(s"Connecting sink $sink to slice $slice")
          bd.connect(slice.DOUT, sink)
      }

      // Now connect the slices to this port
      idxToSlice.values.foreach { slice =>
        soct.log.debug(s"Connecting slice ${slice.instanceName} to $this")
        bd.connect(this, slice.DIN)

      }
    }
  }

  /**
   * Use this reset to connect to peripherals needing an active-low / negative polarity reset.
   * Deassertion is synchronized to the slowestSyncClk.
   */
  object PeripheralAResetN extends BdPin("peripheral_aresetn", ProcSysReset.this) with ProcSysResetPort with ResetN {
    override val maxOutputs: Int = 16
  }

  /**
   * Use this reset to connect to peripherals needing an active-high / positive polarity reset.
   * Deassertion is synchronized to the slowestSyncClk.
   */
  object PeripheralReset extends BdPin("peripheral_reset", ProcSysReset.this) with ProcSysResetPort with Reset {
    override val maxOutputs: Int = 16
  }

  /**
   * Bus Structures reset - for example, arbiters for bridges. Active-High
   */
  object BusStructReset extends BdPin("bus_struct_reset", ProcSysReset.this) with ProcSysResetPort with Reset {
    override val maxOutputs: Int = 8
  }

  /**
   * Interconnect reset, for example, interconnects with active-Low reset inputs.
   */
  object InterconnectResetN extends BdPin("interconnect_aresetn", ProcSysReset.this) with ProcSysResetPort with ResetN {
    override val maxOutputs: Int = 8
  }

  /**
   * DCM Locked input - connect to the DCM or PLL lock output driving the slowestSyncClk
   */
  object DCM_LOCKED extends BdPin("dcm_locked", ProcSysReset.this)


  override def defaultProperties: Map[String, String] = {
    Map(
      "CONFIG.C_NUM_PERP_ARESETN" -> PeripheralAResetN.dinWidth.toString,
      "CONFIG.C_NUM_PERP_RST" -> PeripheralReset.dinWidth.toString,
      "CONFIG.C_NUM_BUS_RST" -> BusStructReset.dinWidth.toString,
      "CONFIG.C_NUM_INTERCONNECT_ARESETN" -> InterconnectResetN.dinWidth.toString
    )
  }
}


object ProcSysReset {

}