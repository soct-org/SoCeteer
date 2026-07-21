package soct.system.vivado

import freechips.rocketchip.amba.axi4.AXI4SlaveParameters
import org.chipsalliance.cde.config.Parameters
import soct.system.soceteer.SOCTSystem
import soct.system.vivado.abstracts._
import soct.system.vivado.components._
import soct.system.vivado.fpga.DDR4PortParams
import soct.system.vivado.misc.AXI4BusInfo

import scala.annotation.unused


/**
 * Information about a DDR4 memory controller and its associated AXI4 bus.
 *
 * @param param         Several parameters describing the DDR4 memory controller, including its name and offset in the memory map
 * @param ddr4Intf      The interface to the board's DDR4 controller
 * @param mAxi          Info about the master's (the processor's) axi interface
 * @param deinterleaver Optional address deinterleaver sitting between the master and the DDR4 controller.
 *                      Present when the memory channels are cache-line interleaved (multi-channel designs):
 *                      it compacts the channel's sparse address view onto a dense range starting at the
 *                      memory base, so the configured channel offset is ignored in that case.
 */
case class DDR4Info(param: DDR4PortParams, ddr4Intf: BdIntfPort, mAxi: AXI4BusInfo,
                    deinterleaver: Option[AXIAddrDeinterleaver] = None) {

  /**
   * The single AXI4 slave the processor-side bus exposes for this memory channel.
   *
   * @return the slave parameters
   * @throws VivadoDesignException if the bus carries no or multiple slaves, or is a master
   */
  @unused // library API
  def slaveParam: AXI4SlaveParameters = mAxi.axiParams.fold(
    sp => {
      if (sp.slaves.length != 1) {
        throw VivadoDesignException(s"AXI4 Slave has ${sp.slaves.length} slaves, but only one is supported.")
      }
      sp.slaves.head
    },
    _ => throw VivadoDesignException(s"AXI4 Slave is not a slave, but a master.")
  )

  /**
   * The AXI address space from which the DDR4 controller's address segment is reached:
   * the deinterleaver's dense-side master port if present, otherwise the processor's port directly.
   */
  def axiAddrSpacePin: BdIntfPin = deinterleaver.map(d => d.M_AXI: BdIntfPin).getOrElse(mAxi.bdPin)

}

/**
 * One memory channel of the design: the DDR4 controller instance and the SmartConnect
 * bridging the processor's memory AXI port (clock-domain crossing + width conversion) to it.
 *
 * @param ddr4Inst the DDR4 controller component
 * @param memSMC   the SmartConnect in front of the controller's S_AXI
 */
case class MemPath(ddr4Inst: DDR4, memSMC: AXISmartConnect)


/**
 * Capability marker for top-level systems that support multiple memory channels
 * (a [[soct.RegisteredMems]] layout with more than one entry). The launcher checks it via
 * reflection on the selected top class (see [[hasMultiMemSupport]]) to pick between the
 * single- and multi-channel memory layout fragments; tops without the marker always get the
 * single-channel layout.
 */
trait SupportsMultiMem


/**
 * Shared base of Vivado top-level systems, assembled from one trait per concern:
 *   - [[SOCTVivadoSystemDTS]] binds the common MMIO devices into the device tree (runs at
 *     construction time, in a fixed order - it is mixed in first so the builder gate and
 *     the resource bindings initialize exactly as they always have)
 *   - [[SOCTVivadoSystemConstraints]] provides the TCL timing-constraint helpers
 *   - [[SOCTVivadoSystemWiring]] builds the topology-independent components and wiring
 *     ([[SOCTVivadoSystemWiring.initCommonDesign]] and the `wire*` helpers)
 *
 * A concrete system ([[SOCTVivadoSystem]] being the standard one) only adds its memory
 * topology and clock synthesis.
 *
 * @throws VivadoDesignException during construction if no BdBuilder is set in the parameters
 *                               or the system has no PLIC for interrupt wiring
 */
abstract class SOCTVivadoSystemBase(implicit p: Parameters) extends SOCTSystem
  with SOCTVivadoSystemDTS
  with SOCTVivadoSystemConstraints
  with SOCTVivadoSystemWiring
