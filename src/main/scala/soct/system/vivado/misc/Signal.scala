package soct.system.vivado.misc

import freechips.rocketchip.amba.axi4.AXI4Bundle
import freechips.rocketchip.prci.ClockBundle
import freechips.rocketchip.tilelink.TLBusWrapper
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts.{BdChiselPin, BdIntfPin, BdPinBase, BdPinInOut, MapsToPorts, XSignal}

import scala.collection.mutable


class AssociatedBus(val bus: TLBusWrapper, val bdPin: BdPinBase)


case class AssociatedAXIBus(override val bus: TLBusWrapper, override val bdPin: BdIntfPin, axiBundle: AXI4Bundle) extends AssociatedBus(bus, bdPin)


case class ClkDesc(clkPin: BdChiselPin,
                   assocRstPin: BdChiselPin,
                   assocBusIfs: Seq[AssociatedBus],
                   buses: Seq[TLBusWrapper],
                   freqHz: Option[BigInt] = None)


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

        if (desc.assocBusIfs.nonEmpty) {
          // If there are associated bus interfaces we need to have frequency information for the clock
          val freqHz = desc.freqHz.getOrElse {
            throw new Exception(s"Clock ${desc.clkPin} has associated bus interfaces but no frequency information provided in $desc. This will result in an error in Vivado.")
          }
          params ++= Seq(s"FREQ_HZ $freqHz", s"ASSOCIATED_BUSIF ${desc.assocBusIfs.map(_.bdPin.pin).mkString(":")}")
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