package soct.system.vivado.abstracts

/**
 * Trait for Board Design Pin Ports - used to connect component pins
 */
abstract class BdPinBase(pinFn: => String, instFn: => BdComp) extends BdPinPort {
  def pin: String = pinFn

  override def parentInst(): BdComp = instFn

  override def ref: String = s"${parentInst().instanceName}/$pinFn"

  override val vivadoKind: VivadoHandleKind = VivadoHandleKind.ScalarPin // Overridden in BdIntfPin - different retrieval
}

/**
 * Board Design Input Pin
 */
class BdPinIn(pinFn: => String, instFn: => BdComp) extends BdPinBase(pinFn, instFn) with DrivenByNet


/**
 * Board Design Output Pin
 */
class BdPinOut(pinFn: => String, instFn: => BdComp) extends BdPinBase(pinFn, instFn) with DrivesNet


/**
 * Board Design Input/Output Pin (bidirectional)
 */
class BdPinInOut(pinFn: => String, instFn: => BdComp) extends BdPinBase(pinFn, instFn) with BiDirNet


/**
 * Board Design Interface Pin (no direction)
 */
class BdIntfPin(pinFn: => String, instFn: => BdComp) extends BdPinBase(pinFn, instFn) {
  override val vivadoKind: VivadoHandleKind = VivadoHandleKind.IntfPin
}
