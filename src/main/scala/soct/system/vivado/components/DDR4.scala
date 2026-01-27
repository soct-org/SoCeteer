package soct.system.vivado.components

import freechips.rocketchip.subsystem.{CanHaveMasterAXI4MemPort, ExtMem}
import org.apache.commons.lang3.NotImplementedException
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTVivado.{DEFAULT_MEMORY_ADDR_32, DEFAULT_MEMORY_ADDR_64}
import soct.system.vivado.fpga.{DDR4Port, FPGAClockDomain, FPGAResetPortType}
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}
import soct.system.vivado.components.DDR4._

import scala.collection.mutable

/**
 *
 * @param cds The clock domains to which this DDR4 component will output clocks. Up to 4 additional clock outputs can be specified.
 * @param dom The clock domain in which this DDR4 component is instantiated - for now, must be an FPGAClockDomain
 */
case class DDR4(override val cds: Seq[ClockDomain])(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[FPGAClockDomain])
  extends InstantiableBdComp with IsXilinxIP with SourceForPins with HasSinkPins with AutoConnect with ProvidesAutoClock {

  override def partName: String = "xilinx.com:ip:ddr4:2.2"

  override def clockInPorts: Seq[BdPinBase] = Seq(BdPin(C0_SYS_CLK, this))

  override def defaultProperties: Map[String, String] = {
    val props = mutable.Map.empty[String, String]

    dom.foreach {
      case fpgaDom: FPGAClockDomain =>
        val ddr4Intfs = sourcePins.map(_.inst).collect { case ddr4Port: DDR4Port => ddr4Port }
        require(ddr4Intfs.size == 1, s"DDR4 component $this must have exactly one DDR4Port source pin, found ${ddr4Intfs.size}")
        props += "CONFIG.C0_DDR4_BOARD_INTERFACE" -> ddr4Intfs.head.instanceName
        props += "CONFIG.C0_CLOCK_BOARD_INTERFACE" -> fpgaDom.port.instanceName
        fpgaDom.reset.foreach {
          case r: FPGAResetPortType =>
            props += "CONFIG.RESET_BOARD_INTERFACE" -> r.instanceName
          case _ => // Ignore other reset types for now
        }
      case _ =>
        throw XilinxDesignException(s"DDR4 must be instantiated in an FPGAClockDomain")
    }

    val freqs = cds.zipWithIndex.foldLeft(mutable.Map.empty[String, String]) {
      case (acc, (cd, idx)) =>
        acc += clkOutFreq(idx + 1) -> cd.freqMHz.toInt.toString
        acc
    }

    props.toMap ++ freqs
  }

  /**
   * Emit the TCL commands to connect this component in the design
   */
  override def connectTclCommands: Seq[String] = {
    clkTclCommands
  }

  override protected def getPinImpl[T](source: T): Option[BdPinBase] = {
    source match {
      case _: DDR4Port => Some(BdIntfPin(C0_DDR4, this))
      case _ => None
    }
  }

  override def clockOutPortImpl(cd: ClockDomain, domIdx: Int, sinkPin: BdPinBase, pinIdx: Int): BdPin = {
    val clkoutIdx = domIdx + 1
    if (clkoutIdx > 4) {
      throw XilinxDesignException(s"DDR4 only supports up to 4 clock outputs, requested output index $clkoutIdx")
    }
    BdPin(clkOut(clkoutIdx), this)
  }
}


object DDR4 {
  private def clkOut(idx: Int): String = s"addn_ui_clkout$idx"

  private def clkOutFreq(idx: Int): String = s"CONFIG.UI_CLKOUT${idx}_FREQ_HZ"

  private val C0_SYS_CLK = "C0_SYS_CLK"
  private val C0_DDR4 = "C0_DDR4"
}