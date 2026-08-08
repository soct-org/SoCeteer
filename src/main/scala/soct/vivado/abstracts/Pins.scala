package soct.vivado.abstracts

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

  override lazy val ref: String = s"${parentInst.bdPath}/$pin"

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


/**
 * Board Design Chisel Pin (bidirectional at compile time - actual direction determined during elaboration)
 */
class BdChiselPin(pinFn: => String, instFn: => BdComp, chiselPort: => chisel3.Data) extends BdPinInOut(pinFn, instFn)

