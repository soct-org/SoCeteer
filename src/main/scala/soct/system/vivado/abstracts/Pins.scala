package soct.system.vivado.abstracts

/**
 * Trait for Board Design Pin Ports - used to connect component pins
 */
abstract class BdPinBase(pinFn: => String, instFn: => BdComp) extends BdPinPort {

  /**
   * Generate the pin name
   * @return The pin name
   */
  lazy val pin: String = pinFn

  override lazy val parentInst: BdComp = instFn

  override lazy val ref: String = s"${parentInst.instanceName}/$pin"

  override val vivadoKind: VivadoHandleKind = VivadoHandleKind.ScalarPin // Overridden in BdIntfPin - different retrieval
}

/**
 * Pin constructor trait - should be implemented for each generalized pin type
 * @tparam T The type of BdPinBase to construct
 */
trait PinCtor[T <: BdPinBase] {
  def apply(pin: => String, inst: => BdComp): T
}


object PinCtor {
  implicit val inCtor: PinCtor[BdPinIn] = (pin, inst) => new BdPinIn(pin, inst)

  implicit val outCtor: PinCtor[BdPinOut] = (pin, inst) => new BdPinOut(pin, inst)

  implicit val inOutCtor: PinCtor[BdPinInOut] = (pin, inst) => new BdPinInOut(pin, inst)

  implicit val intfCtor: PinCtor[BdIntfPin] = (pin, inst) => new BdIntfPin(pin, inst)
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


/**
 * Board Design Chisel Pin (bidirectional at compile time - actual direction determined during elaboration)
 * Note: This Pin does not have a default Ctor in PinCtor - it must be constructed manually.
 */
class BdChiselPin(pinFn: => String, instFn: => BdComp, chiselPort: => chisel3.Data) extends BdPinInOut(pinFn, instFn) {
  def chiselPin: chisel3.Data = chiselPort
}

