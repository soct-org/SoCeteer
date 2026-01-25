package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.HasSOCTConfig
import soct.system.vivado.SOCTVivado.portToBdPin
import soct.system.vivado.{SOCTBdBuilder, SOCTVivadoSystem}

// TODO this only works for a single clock domain for now - we should enable multiple clock domains for different buses

class SOCTVivadoSystemTop(s: SOCTVivadoSystem)(implicit p: Parameters, bd: SOCTBdBuilder, dom: Option[ClockDomain])
  extends InstantiableBdComp with IsModule with AutoConnect {

  // This is the top-level instance representing this system in the block design
  private val c = p(HasSOCTConfig)

  // Returns either port refs for clocks or resets depending on the parameter
  private def ioClockOrReset(isReset: Boolean): Seq[BdPin] = {
    val busClockOrResetPorts = s.io_clocks
      .map(_.getWrappedValue)
      .map(_.data) // Option[Iterable[ClockBundle]]
      .toSeq // Seq[Iterable[ClockBundle]] (0 or 1 element)
      .flatten // Seq[ClockBundle]
      .map { bundle =>
        if (isReset) portToBdPin(bundle.reset) else portToBdPin(bundle.clock)
      }

    val debugClockOrResetPort = if (isReset) {
      portToBdPin(s.debug.getWrappedValue.get.reset)
    } else {
      portToBdPin(s.debug.getWrappedValue.get.clock)
    }

    busClockOrResetPorts :+ debugClockOrResetPort
  }

  override def resetInPorts: Seq[BdPinType] = {
    ioClockOrReset(isReset = true) // Active-high resets
  }

  override def clockInPorts: Seq[BdPinType] = {
    ioClockOrReset(isReset = false)
  }

  override def reference: String = c.topModuleName

  override def friendlyName: String = s.instanceName

  override def instanceName: String = friendlyName
}
