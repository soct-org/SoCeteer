package soct.system.vivado.components

import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4MasterNode, AXI4SlaveNode}
import freechips.rocketchip.prci.ClockBundle
import freechips.rocketchip.subsystem.{ControlBusKey, FrontBusKey, InSubsystem, InSystem, MemoryBusKey, PeripheryBusKey, PeripheryBusParams, SBUS, SystemBusKey, TLNetworkTopologyLocated}
import freechips.rocketchip.tilelink.{TLBusWrapper, TLBusWrapperTopology}
import freechips.rocketchip.util.Location
import org.apache.commons.lang3.NotImplementedException
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.ModuleValue
import org.chipsalliance.diplomacy.nodes.HeterogeneousBag
import soct.HasSOCTConfig
import soct.system.soceteer.SOCTSystem
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}
import soct.system.vivado.abstracts._
import soct.system.vivado.misc.{AssociatedAXIBus, ClkDesc, MarkIOClocks}
import soct.system.vivado.abstracts.BdPinPort.{bidirToBidir, portToBdPin}
import soct.system.vivado.intf.AXIMM


// TODO this only works for a single clock domain for now - we should enable multiple clock domains for different buses
class SOCTVivadoSystemTop(val s: SOCTSystem)(implicit p: Parameters, bd: SOCTBdBuilder)
  extends ChiselModuleTop with IsModule {

  private val c = p(HasSOCTConfig)

  private def getAXI4(axi: ModuleValue[HeterogeneousBag[AXI4Bundle]]): AXI4Bundle = {
    val axis = Seq(axi).flatten
    if (axis.size != 1) {
      throw new XilinxDesignException(s"Expected exactly one AXI4 bundle for $axi but found ${axis.size}")
    } else {
      axis.head
    }
  }

  /**
   * Map each TLBusWrapper to its corresponding AXI4Bundle, if it exists.
   * This is used to determine which AXI4 interfaces are associated with which clock domains, so that we can add the appropriate Vivado annotations to the top-level ports.
   * If not overridden, this will default to the mem, mmio, and l2 frontend buses
   */
  lazy val axi4BusMapping: Seq[(TLBusWrapper, AXIMM, AXI4Bundle, Either[AXI4SlaveNode, AXI4MasterNode])] = Seq(
    (s.memAXI4Bus, s.mem_axi4, s.memAXI4Node),
    (s.mmioAXI4Bus, s.mmio_axi4, s.mmioAXI4Node),
    (s.l2FrontendAXI4Bus, s.l2_frontend_bus_axi4, s.l2FrontendAXI4Node)).map {
    case (bus, axiBundle, node) =>
      val axi = getAXI4(axiBundle)
      val bdPin = AXIMM(axi)
      node match {
        case x: AXI4SlaveNode => (bus, bdPin, axi, Left(x))
        case x: AXI4MasterNode => (bus, bdPin, axi, Right(x))
      }

  }

  /**
   * Determine the frequency of a given clock bundle based on the AXI4 interfaces that are associated with it.
   * This is used to add the appropriate Vivado annotations to the top-level ports, so that Vivado can properly constrain the design.
   * By default, we use the keys [[PeripheryBusKey]], [[MemoryBusKey]], [[SystemBusKey]], [[FrontBusKey]] and [[ControlBusKey]]
   * and their dts frequencies to determine the frequency of the clock bundle, but this can be overridden if needed.
   *
   * @param cb   the clock bundle for which to determine the frequency
   * @param desc the clock description for the given clock bundle, which includes the associated AXI4 interfaces and buses
   * @return
   */
  def freqMapping(cb: ClockBundle, desc: ClkDesc): Option[BigInt] = {
    if (desc.freqHz.isDefined) {
      soct.log.debug(s"Using explicitly defined frequency ${desc.freqHz.get} for clock bundle $cb")
      return desc.freqHz
    }

    Some(100 * 1000 * 1000) // default to 100 MHz

  }

  lazy val ioClocksMapping: Map[ClockBundle, ClkDesc] = {

    s.io_clocks.get.getWrappedValue.data.toSeq.map {
      cb =>
        val buses = s.busHierarchyByClock(cb)
        throw new NotImplementedException("This commit is intended to be a proof of concept")
        val desc = ClkDesc(
          clkPin = portToBdPin(cb.clock),
          assocRstPin = portToBdPin(cb.reset),
          assocBusIfs = Seq.empty, // for now, only AXI4 interfaces are supported, but we could easily extend this to support other types of interfaces as well
          buses = Seq.empty) // TODO change

        cb -> desc.copy(freqHz = freqMapping(cb, desc))
    }.toMap
  }

  lazy val INTERRUPTS: BdChiselPin = portToBdPin(s.module.interrupts)

  override protected def finalizeBdImpl(): Unit = {
    MarkIOClocks(ioClocksMapping)
  }

  override def reference: String = c.topModuleName
}
