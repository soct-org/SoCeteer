package soct.system.vivado.misc

import chisel3.Clock
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4MasterPortParameters, AXI4SlavePortParameters}
import freechips.rocketchip.prci.ClockBundle
import freechips.rocketchip.tilelink.TLBusWrapper
import org.chipsalliance.cde.config.Parameters
import soct.SOCTFreq.Freq
import soct.system.vivado.abstracts.BdPinPort.portToBdPin
import soct.system.vivado.{SOCTBdBuilder, VivadoDesignException}
import soct.system.vivado.abstracts.{BdChiselPin, BdIntfPin, BdPinBase, MapsToPorts}

import scala.collection.mutable


/**
 * A TileLink bus of the RocketSystem together with the block-design pin its exported
 * interface appears on.
 *
 * @param bus   the TileLink bus wrapper
 * @param bdPin the block-design pin of the exported interface
 */
class BusInfo(val bus: TLBusWrapper, val bdPin: BdPinBase)


/**
 * An exported AXI4 interface of the RocketSystem: the bus it belongs to, its top-level pin,
 * the Chisel bundle, and its diplomacy port parameters (slave or master side).
 */
case class AXI4BusInfo(override val bus: TLBusWrapper, override val bdPin: BdIntfPin, axiBundle: AXI4Bundle, axiParams: Either[AXI4SlavePortParameters, AXI4MasterPortParameters]) extends BusInfo(bus, bdPin)


/**
 * Description of a clock domain to annotate the top-level ports with the appropriate Vivado interface information.
 * @param clkPin the clock pin associated with this clock domain
 * @param assocRstPin the reset pin associated with this clock domain
 * @param assocAXI4Ifs the AXI4 interfaces associated with this clock domain, which will be used to determine the frequency of the clock domain for Vivado annotations. This is optional but if provided, the frequency must also be provided.
 * @param buses the TL buses associated with this clock domain, which can be used for informational purposes but are not currently used for any annotations. This is optional and is not used for any annotations, but can be useful for keeping track of which buses are associated with which clock domains.
 * @param freq The frequency of the clock domain. This is optional, but if there are associated AXI4 interfaces, this must be provided so that the appropriate Vivado annotations can be added to the top-level ports.
 */
case class ClkDesc(clkPin: BdChiselPin,
                   assocRstPin: BdChiselPin,
                   assocAXI4Ifs: Seq[AXI4BusInfo],
                   buses: Seq[TLBusWrapper],
                   freq: Option[Freq] = None)


/** Xilinx signal VLNVs used in the X_INTERFACE_INFO port annotations. */
private object SignalVlnv {
  val clock = "xilinx.com:signal:clock:1.0"
  val reset = "xilinx.com:signal:reset:1.0"
}

/**
 * Annotates raw Chisel clock/reset ports of the top module with Vivado X_INTERFACE_INFO
 * attributes, so Vivado recognises them as clock and reset pins (used for the debug clock
 * and resets, which are not part of a [[ClkDesc]] clock domain).
 *
 * @param clocks     the Chisel clock ports to annotate
 * @param resets     the Chisel reset ports to annotate (assumed active-high, the Chisel default)
 * @param clockGroup Vivado interface group name for the clocks
 * @param resetGroup Vivado interface group name for the resets
 */
case class MarkClockAndResets(clocks: Seq[Clock], resets: Seq[chisel3.Reset], clockGroup: String = "CLOCK", resetGroup: String = "RESET")
                             (implicit val bd: SOCTBdBuilder, p: Parameters) extends MapsToPorts {

  override def portMapping: Map[String, Seq[String]] = {
    val clkMaps = clocks.flatMap { clk =>
      val info = s"""(* X_INTERFACE_INFO = "${SignalVlnv.clock} $clockGroup CLK" *)"""
      Map(portToBdPin(clk).pin -> Seq(info))
    }.toMap

    val rstMaps = resets.flatMap { rst =>
      val info = s"""(* X_INTERFACE_INFO = "${SignalVlnv.reset} $resetGroup RST" *)"""
      // All chisel Resets are active high by default
      val param = s"""(* X_INTERFACE_PARAMETER = "POLARITY ACTIVE_HIGH" *)"""
      Map(portToBdPin(rst).pin -> Seq(info, param))
    }.toMap

    clkMaps ++ rstMaps
  }
}

/**
 * Annotates the top module's clock-domain ports with Vivado X_INTERFACE attributes: each
 * clock gets its FREQ_HZ, its associated reset, and the AXI4 interfaces it drives
 * (ASSOCIATED_BUSIF), so Vivado constrains the exported interfaces correctly.
 *
 * @param io         description of every clock domain, keyed by its clock bundle
 * @param resetGroup Vivado interface group name for the resets
 * @param clockGroup Vivado interface group name for the clocks
 */
case class MarkIOClocks(io: Map[ClockBundle, ClkDesc], resetGroup: String = "RESET", clockGroup: String = "CLOCK")
                       (implicit val bd: SOCTBdBuilder, p: Parameters) extends MapsToPorts {

  /**
   * @throws soct.system.vivado.VivadoDesignException if a clock domain has associated AXI4
   *                                                  interfaces but no frequency information
   */
  override def portMapping: Map[String, Seq[String]] = {
    val maps = io.iterator.map { case (_, desc) =>
      val assocRstPin = desc.assocRstPin.pin
      val clockMap = {
        val info = s"""(* X_INTERFACE_INFO = "${SignalVlnv.clock} $clockGroup CLK" *)"""
        val params = mutable.ListBuffer[String]()

        if (desc.assocAXI4Ifs.nonEmpty) { // For now, we only support associating AXI4 interfaces
          // If there are associated bus interfaces we need to have frequency information for the clock
          val freq = desc.freq.getOrElse {
            throw VivadoDesignException(s"Clock ${desc.clkPin} has associated bus interfaces but no frequency information provided in $desc. This will result in an error in Vivado.")
          }
          params ++= Seq(s"FREQ_HZ ${freq.toHz.toLong}", s"ASSOCIATED_BUSIF ${desc.assocAXI4Ifs.map(_.bdPin.pin).mkString(":")}")
        }
        params += s"ASSOCIATED_RESET $assocRstPin"

        val param = s"""(* X_INTERFACE_PARAMETER = "${params.mkString(", ")}" *)"""
        Map(desc.clkPin.pin -> Seq(info, param))
      }

      val resetMap = {
        val info = s"""(* X_INTERFACE_INFO = "${SignalVlnv.reset} $resetGroup RST" *)"""
        // All chisel Resets are active high by default
        val param = s"""(* X_INTERFACE_PARAMETER = "POLARITY ACTIVE_HIGH" *)"""
        Map(assocRstPin -> Seq(info, param))
      }
      clockMap ++ resetMap
    }
    maps.flatten.toMap
  }
}