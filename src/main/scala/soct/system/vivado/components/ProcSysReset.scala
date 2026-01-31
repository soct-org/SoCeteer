package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, TCLCommands}
import soct.system.vivado.abstracts.{ResetSource, _}

import scala.collection.mutable

/**
 * Proc Sys Reset IP core from Xilinx.
 * Documentation: https://docs.amd.com/v/u/en-US/pg164-proc-sys-reset
 *
 * @param dom Only used for the slowestSyncClk connection
 */
case class ProcSysReset()(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain])
  extends BdComp with Xip with ReceivesClock {

  override def partName: String = "xilinx.com:ip:proc_sys_reset:5.0"

  override def clockInPorts: () => Seq[BdPinPort] = () => Seq(BdPin("slowest_sync_clk", this))


  abstract class ProcSysResetPort extends ResetSource with Finalizable {
    val maxOutputs: Int

    val portName: String

    lazy val dinWidth: Int = getIOs.size min maxOutputs max 1 // Every ProcSysReset port has at least one output

    override def getIO: BdPinPort = BdPin(friendlyName, ProcSysReset.this)

    override protected def finalizeBdImpl(): (Seq[BdComp], TCLCommands) = {
      val idxToSlice = mutable.Map.empty[Int, InlineSlice]
      var connections: TCLCommands = Seq.empty
      val sinks = getIOs.toSeq
      if (dinWidth < 2) {
        return (Seq.empty, Seq.empty) // No slicing needed
      } else {
        // Clear all connections to the pins themselves, will be reconnected via slices and slices are connected to the pins
        clearIOs()
      }

      soct.log.debug(s"Slicing $portName of ${ProcSysReset.this.instanceName} into $dinWidth slices for ${sinks.size} sinks")

      // Go through each io and calc which slice it goes to (idx % maxOutputs) to have even distribution
      sinks.zipWithIndex.foreach {
        case (sink, i) =>
          val sliceIdx = i % maxOutputs
          val slice = idxToSlice.getOrElseUpdate(sliceIdx,
            new InlineSlice(dinWidth, sliceIdx, sliceIdx, 1) {
              override def instanceName: String = s"${ProcSysReset.this.instanceName}_${portName}_slice_$sliceIdx"
            }
          )
          connections :+= BdPinPort.connect1(slice.getSource, sink)
      }
      (idxToSlice.values.toSeq, connections)
    }
  }


  /**
   * Use this reset to connect to peripherals needing an active-low / negative polarity reset.
   * Deassertion is synchronized to the slowestSyncClk.
   */
  object PeripheralAResetN extends ProcSysResetPort with ResetN {
    override val maxOutputs: Int = 16

    override val portName: String = "peripheral_aresetn"
  }

  /**
   * Use this reset to connect to peripherals needing an active-high / positive polarity reset.
   * Deassertion is synchronized to the slowestSyncClk.
   */
  object PeripheralReset extends ProcSysResetPort with Reset {

    override val maxOutputs: Int = 16

    override val portName: String = "peripheral_reset"
  }

  /**
   * Bus Structures reset - for example, arbiters for bridges. Active-High
   */
  object BusStructReset extends ProcSysResetPort with Reset {

    override val maxOutputs: Int = 8

    override val portName: String = "bus_struct_reset"
  }

  /**
   * Interconnect reset, for example, interconnects with active-Low reset inputs.
   */
  object InterconnectResetN extends ProcSysResetPort with ResetN {

    override val maxOutputs: Int = 8

    override val portName: String = "interconnect_aresetn"
  }

  /**
   * DCM Locked input - connect to the DCM or PLL lock output driving the slowestSyncClk
   */
  object DCM_LOCKED extends SingleIO {
    override def getIO: BdPinPort = BdPin("dcm_locked", ProcSysReset.this)
  }


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