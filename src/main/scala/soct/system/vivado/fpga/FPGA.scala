package soct.system.vivado.fpga

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts.{HasFriendlyName, IsXilinx}
import soct.system.vivado.components.ZynqUltraPS
import soct.system.vivado.misc.{FPGAPMODPin, RawPMODPin}

/**
 * Trait that provides a singleton instance of the Zynq UltraScale+ MPSoC processing system (PS) for FPGA designs.
 */
trait HasZynqUltraPS {

  private var PSOpt = Option.empty[ZynqUltraPS]

  /**
   * Get the singleton instance of the Zynq UltraScale+ MPSoC processing system (PS) for this design.
   * If the PS has not been instantiated yet, it will be created and stored for future retrieval. This ensures that only one instance of the PS exists in the design.
   * @return The singleton instance of the ZynqUltraPS
   */
  def getZynqUltraPS()(implicit bd: SOCTBdBuilder, p: Parameters): ZynqUltraPS = {
    PSOpt.getOrElse {
      val ps = ZynqUltraPS()
      PSOpt = Some(ps)
      ps
    }
  }
}



/**
 * Abstract base class for FPGA boards. Subclasses must provide information about the specific FPGA board,
 * such as the Xilinx part number, available clock domains, DDR4 ports, and PMOD ports.
 * For instance, see the ZCU104 implementation.
 * All init functions have side-effects, and should be called only once per block design.
 */
abstract class FPGA extends IsXilinx with HasFriendlyName {

  /**
   * The Xilinx part number for this FPGA board - e.g., "xczu7ev-ffvc1156-2-e"
   */
  val xilinxPart: String

  /**
   * The PMOD ports available on this FPGA board
   */
  val getPMODPorts: Seq[Int] = Seq.empty

  /**
   * Get the PMOD pin corresponding to the given PMOD port and pin index.
   *
   * @param pmodPort The PMOD port number (e.g., 0, 1, 2) to which the component is connected in the block design
   * @param pmodPin  The index of the PMOD pin within the specified PMOD port (e.g., 0-7 for an 8-pin PMOD)
   * @throws soct.system.vivado.VivadoDesignException if the specified PMOD port or pin index is not defined for this FPGA board
   * @return The PmodPin object representing the specified PMOD pin
   */
  def pmod(pmodPort: Int, pmodPin: RawPMODPin): FPGAPMODPin

  /** The board's internal (soldered on-board) DDR4 memory ports. */
  def intDDR4Ports: Seq[DDR4PortParams] = Seq.empty

  /** The board's external (user-insertable DIMM) DDR4 memory ports. */
  def extDDR4Ports: Seq[DDR4PortParams] = Seq.empty

  /** The board's UART ports. */
  def uartPorts: Seq[UARTPortParams] = Seq.empty

  /**
   * Create the board's clock (and associated reset) ports for `n` clock domains.
   * Side-effectful: instantiates the ports in the block design; call once per design.
   *
   * @param n the number of clock domains the design needs
   * @return one [[FPGAClockDomain]] per requested domain
   * @throws soct.system.vivado.VivadoDesignException if the board cannot provide `n` clock inputs
   */
  def initNClockPorts(n: Int)(implicit bd: SOCTBdBuilder, p: Parameters): Seq[FPGAClockDomain]

  override def toString: String = friendlyName
}
