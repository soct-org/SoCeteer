package soct.system.vivado.misc

import chisel3.Clock
import freechips.rocketchip.amba.axi4.AXI4Bundle
import freechips.rocketchip.prci.ClockBundle
import freechips.rocketchip.tilelink.TLBusWrapper
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.abstracts.BdPinPort.portToBdPin
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}
import soct.system.vivado.abstracts.{BdChiselPin, BdIntfPin, BdPinBase, MapsToPorts, XSignal}

import scala.collection.mutable


class BusInfo(val bus: TLBusWrapper, val bdPin: BdPinBase)


case class AXI4BusInfo(override val bus: TLBusWrapper, override val bdPin: BdIntfPin, axiBundle: AXI4Bundle) extends BusInfo(bus, bdPin)


/**
 * Description of a clock domain for the purposes of annotating the top-level ports with the appropriate Vivado interface information.
 * @param clkPin the clock pin associated with this clock domain
 * @param assocRstPin the reset pin associated with this clock domain
 * @param assocAXI4Ifs the AXI4 interfaces associated with this clock domain, which will be used to determine the frequency of the clock domain for Vivado annotations. This is optional but if provided, the frequency must also be provided.
 * @param buses the TL buses associated with this clock domain, which can be used for informational purposes but are not currently used for any annotations. This is optional and is not used for any annotations, but can be useful for keeping track of which buses are associated with which clock domains.
 * @param freqHz The frequency of the clock domain in Hz. This is optional, but if there are associated AXI4 interfaces, this must be provided so that the appropriate Vivado annotations can be added to the top-level ports.
 */
case class ClkDesc(clkPin: BdChiselPin,
                   assocRstPin: BdChiselPin,
                   assocAXI4Ifs: Seq[AXI4BusInfo],
                   buses: Seq[TLBusWrapper],
                   freqHz: Option[BigInt] = None)


case class MarkClockAndResets(clocks: Seq[Clock], resets: Seq[chisel3.Reset], clockGroup: String = "CLOCK", resetGroup: String = "RESET")
                             (implicit val bd: SOCTBdBuilder, p: Parameters) extends MapsToPorts {

  private def toClk(clock: Clock) = new XSignal {
    override def partName: String = "xilinx.com:signal:clock:1.0"
  }

  private def toRst(reset: chisel3.Reset) = new XSignal {
    override def partName: String = "xilinx.com:signal:reset:1.0"
  }

  override def portMapping: Map[String, Seq[String]] = {
    val clkMaps = clocks.flatMap { clk =>
      val clkSignal = toClk(clk)
      val info = s"""(* X_INTERFACE_INFO = "${clkSignal.partName} $clockGroup CLK" *)"""
      Map(portToBdPin(clk).pin -> Seq(info))
    }.toMap

    val rstMaps = resets.flatMap { rst =>
      val rstSignal = toRst(rst)
      val info = s"""(* X_INTERFACE_INFO = "${rstSignal.partName} $resetGroup RST" *)"""
      // All chisel Resets are active high by default
      val param = s"""(* X_INTERFACE_PARAMETER = "POLARITY ACTIVE_HIGH" *)"""
      Map(portToBdPin(rst).pin -> Seq(info, param))
    }.toMap

    clkMaps ++ rstMaps
  }
}

case class MarkIOClocks(io: Map[ClockBundle, ClkDesc], resetGroup: String = "RESET", clockGroup: String = "CLOCK")
                       (implicit val bd: SOCTBdBuilder, p: Parameters) extends MapsToPorts {

  private def toClk(clk: BdChiselPin) = new XSignal {
    override def partName: String = "xilinx.com:signal:clock:1.0"
  }

  private def toRst(rst: BdChiselPin) = new XSignal {
    override def partName: String = "xilinx.com:signal:reset:1.0"
  }

  override def portMapping: Map[String, Seq[String]] = {
    val maps = io.iterator.map { case (_, desc) =>
      val assocRstPin = desc.assocRstPin.pin
      val clockMap = {
        val clkSignal = toClk(desc.clkPin)
        val info = s"""(* X_INTERFACE_INFO = "${clkSignal.partName} $clockGroup CLK" *)"""
        val params = mutable.ListBuffer[String]()

        if (desc.assocAXI4Ifs.nonEmpty) { // For now, we only support associating AXI4 interfaces
          // If there are associated bus interfaces we need to have frequency information for the clock
          val freqHz = desc.freqHz.getOrElse {
            throw XilinxDesignException(s"Clock ${desc.clkPin} has associated bus interfaces but no frequency information provided in $desc. This will result in an error in Vivado.")
          }
          params ++= Seq(s"FREQ_HZ $freqHz", s"ASSOCIATED_BUSIF ${desc.assocAXI4Ifs.map(_.bdPin.pin).mkString(":")}")
        }
        params += s"ASSOCIATED_RESET $assocRstPin"

        val param = s"""(* X_INTERFACE_PARAMETER = "${params.mkString(", ")}" *)"""
        Map(desc.clkPin.pin -> Seq(info, param))
      }

      val resetMap = {
        val rstSignal = toRst(desc.assocRstPin)
        val info = s"""(* X_INTERFACE_INFO = "${rstSignal.partName} $resetGroup RST" *)"""
        // All chisel Resets are active high by default
        val param = s"""(* X_INTERFACE_PARAMETER = "POLARITY ACTIVE_HIGH" *)"""
        Map(assocRstPin -> Seq(info, param))
      }
      clockMap ++ resetMap
    }
    maps.flatten.toMap
  }
}