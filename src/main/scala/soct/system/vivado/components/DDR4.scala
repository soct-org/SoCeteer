package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.fpga.{DDR4Port, FPGAClockDomain, FPGAResetPortType}
import soct.system.vivado.{SOCTBdBuilder, TCLCommands, XilinxDesignException}
import soct.system.vivado.components.DDR4._
import soct.system.vivado.abstracts._

import scala.collection.mutable

/**
 * DDR4 memory controller component for Xilinx FPGAs.
 *
 * @param domains The clock domains to which this DDR4 component will output clocks. Up to 4 additional clock outputs can be specified.
 * @param dom     The clock domain in which this DDR4 component is instantiated - for now, must be an FPGAClockDomain
 */
case class DDR4(override val domains: Seq[ClockDomain])(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[FPGAClockDomain])
  extends BdComp with Xip with SourceForSinks with HasSinkPins with AutoConnect with ProvidesAutoClock {

  require(dom.isDefined, s"DDR4 component must be instantiated in an FPGAClockDomain")

  override def partName: String = "xilinx.com:ip:ddr4:2.2"

  override def clockInPorts: () => Seq[BdPinPort] = () => Seq(BdIntfPin(C0_SYS_CLK, this))

  override def resetNInPorts: () => Seq[BdPinPort] = () => Seq.empty

  override def resetInPorts: () => Seq[BdPinPort] = () => Seq.empty


  override def defaultProperties: Map[String, String] = {
    val props = mutable.Map.empty[String, String]

    val ddr4Intfs = sourcePins.collect { case ddr4Port: DDR4Port => ddr4Port }
    require(ddr4Intfs.size == 1, s"DDR4 component $this must have exactly one DDR4Port source pin, found ${ddr4Intfs.size}")
    props += "CONFIG.C0_DDR4_BOARD_INTERFACE" -> ddr4Intfs.head.instanceName
    props += "CONFIG.C0_CLOCK_BOARD_INTERFACE" -> dom.get.port.instanceName
    dom.get.reset.foreach {
      case r: FPGAResetPortType =>
        props += "CONFIG.RESET_BOARD_INTERFACE" -> r.instanceName
      case _ => // Ignore other reset types for now
    }

    val freqs = domains.zipWithIndex.foldLeft(mutable.Map.empty[String, String]) {
      case (acc, (cd, idx)) =>
        acc += clkOutFreq(idx + 1) -> cd.freqMHz.toInt.toString
        acc
    }
    props.toMap ++ freqs
  }


  override protected def getPinImpl(source: SourceForSinks, sinkKey: KeyForSink): Option[BdPinPort] = {
    source match {
      case _: DDR4Port => Some(BdIntfPin(C0_DDR4, this))
      case _ => None
    }
  }

  override def outPortImpl(cd: ClockDomain, domIdx: Int, sinkPin: BdPinPort, pinIdx: Int): BdPin = {
    val clkoutIdx = domIdx + 1
    if (clkoutIdx > 4) {
      throw XilinxDesignException(s"DDR4 only supports up to 4 clock outputs, requested output index $clkoutIdx")
    }
    BdPin(clkOut(clkoutIdx), this)
  }

  override protected def connectToSinksImpl: TCLCommands = {
    Seq.empty // For now, only has clock outputs (will change in future)
  }
}


object DDR4 {


  private def clkOut(idx: Int): String = s"addn_ui_clkout$idx"
  private def clkOutFreq(idx: Int): String = s"CONFIG.ADDN_UI_CLKOUT${idx}_FREQ_HZ"
  private val C0_SYS_CLK = "C0_SYS_CLK"
  private val C0_DDR4 = "C0_DDR4"
}