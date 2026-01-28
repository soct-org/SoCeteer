package soct.system.vivado.abstracts

abstract class BdPinBase (portFn: => String, instFn: => BdComp) extends BdPinPort {
  override def port(): String = portFn

  override def parentInst(): BdComp = instFn

  override def ref: String = s"${parentInst().instanceName}/${port()}"
}

class BdPin (portFn: => String, instFn: => BdComp) extends BdPinBase(portFn, instFn) {}

object BdPin {
  def apply(portFn: => String, instFn: => BdComp): BdPin = new BdPin(portFn, instFn)
}


class BdIntfPin (portFn: => String, instFn: => BdComp) extends BdPinBase(portFn, instFn) {}

object BdIntfPin {
  def apply(portFn: => String, instFn: => BdComp): BdIntfPin = new BdIntfPin(portFn, instFn)
}
