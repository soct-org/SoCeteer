package soct.system.vivado.signal

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.abstracts.BdPinPort.portToPortName
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts.{MapsToPorts, XSignal}

import scala.collection.mutable

abstract class SignalPort[T <: chisel3.Data](implicit bd: SOCTBdBuilder, p: Parameters) extends MapsToPorts with XSignal {

  private var gens: mutable.Seq[() => Seq[T]] = mutable.Seq.empty

  override def portMapping: Map[String, Seq[String]] = {
    val signals = gens.flatMap(gen => gen())
    val portMappings = mutable.Map.empty[String, Seq[String]]
    signals.foreach { port =>
      val portName = portToPortName(port)
      portMappings(portName) = getAnnotations(port, portName)
    }
    portMappings.toMap
  }

  def add(gen: () => Seq[T]): Unit = {
    gens :+= gen
  }

  def getAnnotations(port: T, portName: String): Seq[String]
}

case class CLOCK(signalGroup: String) (implicit bd: SOCTBdBuilder, p: Parameters) extends SignalPort[chisel3.Clock] {

  override val partName: String = "xilinx.com:signal:clock:1.0"

  override def getAnnotations(port: chisel3.Clock, portName: String): Seq[String] = {
    Seq(s"(* X_INTERFACE_INFO = \"$partName $signalGroup CLK\" *)")
  }
}

case class RESET(signalGroup: String) (implicit bd: SOCTBdBuilder, p: Parameters) extends SignalPort[chisel3.Reset] {

  override val partName: String = "xilinx.com:signal:reset:1.0"

  override def getAnnotations(port: chisel3.Reset, portName: String): Seq[String] = {
    Seq(
      s"(* X_INTERFACE_INFO = \"$partName $signalGroup RST\" *)",
      // All chisel Resets are active high by default
      s"(* X_INTERFACE_PARAMETER = \"POLARITY ACTIVE_HIGH\" *)"
    )
  }
}
