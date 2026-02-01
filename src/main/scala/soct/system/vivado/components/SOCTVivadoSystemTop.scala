package soct.system.vivado.components

import chisel3.{Data, Record}
import org.chipsalliance.cde.config.Parameters
import soct.HasSOCTConfig
import soct.system.soceteer.SOCTSystem
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts.BdPinPort.portToBdPin
import soct.system.vivado.abstracts._
import soct.system.vivado.signal.{CLOCK, RESET}

import scala.reflect.ClassTag


// TODO this only works for a single clock domain for now - we should enable multiple clock domains for different buses
class SOCTVivadoSystemTop(s: SOCTSystem)(implicit p: Parameters, bd: SOCTBdBuilder)
  extends BdComp with IsModule {

  // This is the top-level instance representing this system in the block design
  private val c = p(HasSOCTConfig)

  private var clockIntf: Option[CLOCK] = None

  private var resetIntf: Option[RESET] = None

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


  private def getPort[T <: Data : ClassTag]: Seq[T] = {
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

  private def resetGen: () => Seq[chisel3.Reset] = () => {
    getPort[chisel3.Reset]
  }

  private def clockGen: () => Seq[chisel3.Clock] = () => {
    getPort[chisel3.Clock]
  }
  /*
  override def clockInPorts: () => Seq[BdPinPort] = () => {
    clockGen().map { clk =>
      portToBdPin(clk)
    }
  }

  override def resetInPorts: () => Seq[BdPinPort] = () => {
    resetGen().map { rst =>
      portToBdPin(rst)
    }
  }
  */
  /**
   * Attach a RESET interface to this top-level module
   *
   * @param reset RESET interface to attach
   * @return This
   */
  def withRESET(reset: RESET): this.type = {
    this.resetIntf = Some(reset)
    reset.add(resetGen)
    this
  }

  /**
   * Attach a CLOCK interface to this top-level module
   *
   * @param clock CLOCK interface to attach
   * @return This
   */
  def withCLOCK(clock: CLOCK): this.type = {
    this.clockIntf = Some(clock)
    clock.add(clockGen)
    this
  }

  override def reference: String = c.topModuleName

  override def friendlyName: String = s.instanceName

  override def instanceName: String = friendlyName
}
