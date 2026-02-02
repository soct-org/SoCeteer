package soct.system.vivado.signal

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts.{BdChiselPin, BdPinBase, BdPinInOut, MapsToPorts, XSignal}


abstract class SignalPort[T <: chisel3.Data](val signalGroup: String, val ports: Seq[BdChiselPin])
  (implicit bd: SOCTBdBuilder, p: Parameters) extends MapsToPorts with XSignal {

  override def portMapping: Map[String, Seq[String]] = {
    ports.foldLeft(Map.empty[String, Seq[String]]) { case (m, cp) =>
      m + (cp.pin -> getAnnotations(cp.chiselPin, cp.pin))
    }
  }

  def getAnnotations(data: chisel3.Data, pin: String): Seq[String]
}

case class ClockSignal(override val signalGroup: String, override val ports: Seq[BdChiselPin])
                      (implicit bd: SOCTBdBuilder, p: Parameters) extends SignalPort[chisel3.Clock](signalGroup, ports) {

  override val partName: String = "xilinx.com:signal:clock:1.0"

  override def getAnnotations(data: chisel3.Data, pin: String): Seq[String] = {
    Seq(s"(* X_INTERFACE_INFO = \"$partName $signalGroup CLK\" *)")
  }
}

case class ResetSignal(override val signalGroup: String, override val ports: Seq[BdChiselPin])
                      (implicit bd: SOCTBdBuilder, p: Parameters) extends SignalPort[chisel3.Reset](signalGroup, ports) {

  override val partName: String = "xilinx.com:signal:reset:1.0"

  override def getAnnotations(data: chisel3.Data, pin: String): Seq[String] = {
    Seq(
      s"(* X_INTERFACE_INFO = \"$partName $signalGroup RST\" *)",
      // All chisel Resets are active high by default
      s"(* X_INTERFACE_PARAMETER = \"POLARITY ACTIVE_HIGH\" *)"
    )
  }
}
