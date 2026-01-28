package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.HasSOCTConfig
import soct.system.vivado.SOCTVivado.portToBdPin
import soct.system.vivado.{SOCTBdBuilder, SOCTVivadoSystem}
import soct.system.vivado.abstracts._


// TODO this only works for a single clock domain for now - we should enable multiple clock domains for different buses
class SOCTVivadoSystemTop(s: SOCTVivadoSystem)(implicit p: Parameters, bd: SOCTBdBuilder, dom: Option[ClockDomain])
  extends BdComp with IsModule with AutoConnect {

  // This is the top-level instance representing this system in the block design
  private val c = p(HasSOCTConfig)

  // Returns either port refs for clocks or resets depending on the parameter
  private def ioClockOrReset(isReset: Boolean): Seq[BdPin] = {
    def bus =
      s.io_clocks.toSeq // escape Option
        .map(_.getWrappedValue)
        .flatMap(_.data.toSeq) // Option[ClockBundle] -> Seq[ClockBundle]
        .map { bundle =>
          if (isReset) portToBdPin(bundle.reset)
          else portToBdPin(bundle.clock)
        }

    def debug = {
      val dbg = s.debug.getWrappedValue.get
      if (isReset) portToBdPin(dbg.reset)
      else portToBdPin(dbg.clock)
    }

    bus ++ Seq(debug)
  }

  override def resetInPorts: Seq[BdPinBase] = {
    ioClockOrReset(isReset = true) // Active-high resets
  }

  override def clockInPorts: Seq[BdPinBase] = {
    ioClockOrReset(isReset = false)
  }

  override def reference: String = c.topModuleName

  override def friendlyName: String = s.instanceName

  override def instanceName: String = friendlyName
}
