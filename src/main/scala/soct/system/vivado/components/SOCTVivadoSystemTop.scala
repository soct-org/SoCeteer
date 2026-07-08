package soct.system.vivado.components

import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4MasterNode, AXI4MasterParameters, AXI4MasterPortParameters, AXI4SlaveNode, AXI4SlaveParameters, AXI4SlavePortParameters}
import freechips.rocketchip.prci.ClockBundle
import freechips.rocketchip.subsystem._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.ModuleValue
import org.chipsalliance.diplomacy.nodes.HeterogeneousBag
import soct.HasSOCTConfig
import soct.system.soceteer.SOCTSystem
import soct.system.vivado.abstracts.BdPinPort.portToBdPin
import soct.system.vivado.abstracts._
import soct.system.vivado.intf.AXIMM
import soct.system.vivado.misc.{AXI4BusInfo, ClkDesc, MarkClockAndResets, MarkIOClocks}
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}


// TODO this only works for a single clock domain for now - we should enable multiple clock domains for different buses
class SOCTVivadoSystemTop(val s: SOCTSystem)(implicit p: Parameters, bd: SOCTBdBuilder)
  extends ChiselModuleTop with IsModule {

  private val c = p(HasSOCTConfig)

  /**
   * Map each TLBusWrapper to its corresponding AXI4Bundle, if it exists.
   * This is used to determine which AXI4 interfaces are associated with which clock domains, so that we can add the appropriate Vivado annotations to the top-level ports.
   * If not overridden, this will default to the mem, mmio, and l2 frontend buses
   */
  lazy val axi4BusMapping: Seq[Seq[AXI4BusInfo]] = Seq(
    (s.memAXI4Bus, s.mem_axi4, s.memAXI4Node),
    (s.mmioAXI4Bus, s.mmio_axi4, s.mmioAXI4Node),
    (s.l2FrontendAXI4Bus, s.l2_frontend_bus_axi4, s.l2FrontendAXI4Node)).map {
    case (bus, axiBundles, n) =>
      val axis = Seq(axiBundles).flatten
      n match {
        case node: AXI4SlaveNode =>
          if (node.portParams.size != axis.size)
            throw new XilinxDesignException(s"AXI4 slave node for bus ${bus.name} has ${node.portParams.size} ports, but ${axis.size} AXI4 bundles were found. This is not supported.")
          axis.zip(node.portParams).map { case (axi, params: AXI4SlavePortParameters) => AXI4BusInfo(bus, AXIMM(axi), axi, Left(params)) }
        case node: AXI4MasterNode =>
          if (node.portParams.size != axis.size)
            throw new XilinxDesignException(s"AXI4 master node for bus ${bus.name} has ${node.portParams.size} ports, but ${axis.size} AXI4 bundles were found. This is not supported.")
          axis.zip(node.portParams).map { case (axi, params: AXI4MasterPortParameters) => AXI4BusInfo(bus, AXIMM(axi), axi, Right(params)) }
        case _ => throw new XilinxDesignException(s"AXI4 node for bus ${bus.name} is neither a master nor a slave node, but ${n.getClass.getName}. This is not supported.")
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
    val freqs = desc.buses.flatMap(_.dtsFrequency).distinct
    if (freqs.size > 1) {
      soct.log.warn(s"Multiple frequencies ${freqs.mkString(", ")} found for clock bundle $cb based on associated buses ${desc.buses.map(_.name).mkString(", ")}. This may result in incorrect Vivado annotations. Using the first frequency ${freqs.head} found.")
    }
    if (freqs.isEmpty) {
      soct.log.warn(s"No frequency information found for clock bundle $cb based on associated buses ${desc.buses.map(_.name).mkString(", ")}. This may result in incorrect Vivado annotations.")
    }
    freqs.headOption
  }

  lazy val ioClocksMapping: Map[ClockBundle, ClkDesc] = {
    // The AXI4 interfaces associated with each clock bundle, if any. We use this information to add the appropriate Vivado annotations to the top-level ports.
    val axi4IfByClock = axi4BusMapping.flatten.map { assocBusIf =>
      val cb = s.clockBundleForBus(assocBusIf.bus)
        .getOrElse(throw new XilinxDesignException(s"Could not find clock bundle for bus ${assocBusIf.bus} associated with AXI4 interface ${assocBusIf.bdPin}"))
      cb -> assocBusIf
    }.toMap

    s.io_clocks.get.getWrappedValue.data.toSeq.map {
      cb =>
        val buses = s.busesForClockBundle(cb).getOrElse(Seq.empty).toSeq // The buses driven by this clock bundle, if any
        val desc = ClkDesc(
          clkPin = portToBdPin(cb.clock),
          assocRstPin = portToBdPin(cb.reset),
          assocAXI4Ifs = axi4IfByClock.get(cb).toSeq,
          buses = buses
        )
        cb -> desc.copy(freqHz = freqMapping(cb, desc))
    }.toMap
  }

  lazy val INTERRUPTS: BdChiselPin = portToBdPin(s.module.interrupts)

  override protected def finalizeBdImpl(): Unit = {
    MarkIOClocks(ioClocksMapping)
    MarkClockAndResets(
      s.debug.map(_.clock).toSeq,
      s.debug.map(_.reset).toSeq ++ s.debug.flatMap(_.systemjtag).map(_.reset).toSeq
    )
  }

  override def reference: String = c.topModuleName
}