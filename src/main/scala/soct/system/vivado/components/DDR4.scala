package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.fpga.{DDR4Port, FPGAClockDomain, FPGAResetPortSource}
import soct.system.vivado.{SOCTBdBuilder, TCLCommands, XilinxDesignException}
import soct.system.vivado.abstracts._

import scala.annotation.unused
import scala.collection.mutable


/**
 * DDR4 memory controller component for Xilinx FPGAs.
 *
 * @param domains The clock domains to which this DDR4 component will output clocks. Up to 4 additional clock outputs can be specified.
 * @param dom     The clock domain in which this DDR4 component is instantiated - for now, must be an FPGAClockDomain
 */
case class DDR4(override val domains: Seq[ClockDomain])(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[FPGAClockDomain])
  extends BdComp with Xip with AutoClockAndReset with ProvidesAutoClock with HasAutoConnect[DDR4] {

  require(dom.isDefined, s"DDR4 component must be instantiated in an FPGAClockDomain")

  override def partName: String = "xilinx.com:ip:ddr4:2.2"

  override def clockInPorts: () => Seq[BdPinPort] = () => Seq(BdIntfPin("C0_SYS_CLK", this))

  override def resetNInPorts: () => Seq[BdPinPort] = () => Seq.empty

  override def resetInPorts: () => Seq[BdPinPort] = () => Seq.empty

  object C0_DDR4 extends SingleIO {override def getIO(): BdPinPort = BdIntfPin("C0_DDR4", DDR4.this)}


  override def defaultProperties: Map[String, String] = {
    val props = mutable.Map.empty[String, String]

    val ddr4Intf = C0_DDR4.source.getOrElse(
      throw XilinxDesignException("DDR4 component requires a connected DDR4Port interface")
    )
    props += "CONFIG.C0_DDR4_BOARD_INTERFACE" -> ddr4Intf
    props += "CONFIG.C0_CLOCK_BOARD_INTERFACE" -> dom.get.port.instanceName
    dom.get.reset.foreach {
      case r: FPGAResetPortSource =>
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


  override def outPortImpl(cd: ClockDomain, domIdx: Int, sinkPin: BdPinPort, pinIdx: Int): BdPin = {
    val clkoutIdx = domIdx + 1
    if (clkoutIdx > 4) {
      throw XilinxDesignException(s"DDR4 only supports up to 4 clock outputs, requested output index $clkoutIdx")
    }
    BdPin(clkOut(clkoutIdx), this)
  }
}


object DDR4 {
  private def clkOut(idx: Int): String = s"addn_ui_clkout$idx"
  private def clkOutFreq(idx: Int): String = s"CONFIG.ADDN_UI_CLKOUT${idx}_FREQ_HZ"

  implicit val a: AutoConnect[DDR4, DDR4Port] = (comp: DDR4, port: DDR4Port) => comp.C0_DDR4.connect(port)
}