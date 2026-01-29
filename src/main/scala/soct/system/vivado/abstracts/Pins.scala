package soct.system.vivado.abstracts

/**
 * Trait for Board Design Pin Ports - used to connect component pins
 */
abstract class BdPinBase (pinFn: => String, instFn: => BdComp) extends BdPinPort {
  override def pin(): Option[String] = Some(pinFn)

  override def parentInst(): BdComp = instFn

  override def ref: String = s"${parentInst().instanceName}/${pin().get}"
}

class BdPin (pinFn: => String, instFn: => BdComp) extends BdPinBase(pinFn, instFn) {}

object BdPin {
  def apply(pinFn: => String, instFn: => BdComp): BdPin = new BdPin(pinFn, instFn)
}


class BdIntfPin (pinFn: => String, instFn: => BdComp) extends BdPinBase(pinFn, instFn) {}

object BdIntfPin {
  def apply(pinFn: => String, instFn: => BdComp): BdIntfPin = new BdIntfPin(pinFn, instFn)
}
