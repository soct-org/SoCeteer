package soct.system.vivado.components

import chisel3.{Data, Record}
import org.chipsalliance.cde.config.Parameters
import soct.HasSOCTConfig
import soct.system.soceteer.SOCTSystem
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts._
import soct.system.vivado.signal.{ClockSignal, ResetSignal}

import scala.reflect.ClassTag


// TODO this only works for a single clock domain for now - we should enable multiple clock domains for different buses
class SOCTVivadoSystemTop(s: SOCTSystem)(implicit p: Parameters, bd: SOCTBdBuilder)
  extends BdComp with IsModule with Finalizable {

  private val c = p(HasSOCTConfig)

  private def extractElementsOfType[T <: Data : ClassTag](record: Record): Seq[T] = {
    record.elements.values.collect {
      case data if implicitly[ClassTag[T]].runtimeClass.isInstance(data) => data.asInstanceOf[T]
    }.toSeq
  }

  private def extractElementsOfType[T <: Data : ClassTag](datas: Seq[Data]): Seq[T] = {
    datas.flatMap {
      case record: Record => extractElementsOfType[T](record)
      case data if implicitly[ClassTag[T]].runtimeClass.isInstance(data) => Seq(data.asInstanceOf[T])
      case _ => Seq.empty
    }
  }

  private def getPorts[T <: Data : ClassTag]: Seq[T] = {
    // Autoconnect to bus and debug signals - for now, this can only be done semi-automatically as Bools are Resets and will be connected incorrectly
    val busPorts = s.io_clocks.toSeq
      .map(_.getWrappedValue)
      .flatMap(_.data.toSeq)
      .flatMap(extractElementsOfType[T])
    val dbg = {
      val dbg = s.debug.getWrappedValue.get
      val jtag = dbg.systemjtag.get
      Seq(dbg.reset, dbg.clock, jtag.reset)
    }
    val debugPorts = extractElementsOfType[T](dbg)
    busPorts ++ debugPorts
  }


  override protected def finalizeBdImpl(): Unit = {
    ClockSignal("CLOCK", getPorts[chisel3.Clock])
    ResetSignal("RESET", getPorts[chisel3.Reset])
  }

  override def reference: String = c.topModuleName

  override def friendlyName: String = s.instanceName

  override def instanceName: String = friendlyName
}
