package soct.system.vivado.components

import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4MasterNode, AXI4MasterParameters, AXI4MasterPortParameters, AXI4SlaveNode, AXI4SlaveParameters, AXI4SlavePortParameters}
import freechips.rocketchip.prci.ClockBundle
import freechips.rocketchip.subsystem._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.ModuleValue
import org.chipsalliance.diplomacy.nodes.HeterogeneousBag
import soct.HasSOCTConfig
import soct.SOCTFreq.Freq
import soct.system.soceteer.SOCTSystem
import soct.system.vivado.abstracts.BdPinPort.portToBdPin
import soct.system.vivado.abstracts._
import soct.system.vivado.intf.AXIMM
import soct.system.vivado.misc.{AXI4BusInfo, ClkDesc, MarkClockAndResets, MarkIOClocks}
import soct.system.vivado.{SOCTBdBuilder, VivadoDesignException}

import scala.collection.immutable.SeqMap


/**
 * The elaborated RocketSystem as a block-design module: exposes the system's AXI4 buses and
 * clock bundles to the DSL and, during finalization, annotates the top-level Verilog ports
 * with Vivado clock/reset/interface attributes.
 *
 * @param s the elaborated SOCT system
 */
// TODO this only works for a single clock domain for now - we should enable multiple clock domains for different buses
class SOCTVivadoSystemTop(val s: SOCTSystem)(implicit p: Parameters, bd: SOCTBdBuilder)
  extends ChiselModuleTop with IsModule {

  private val c = p(HasSOCTConfig)

  /**
   * Map each TLBusWrapper to its corresponding AXI4Bundle, if it exists.
   * This is used to determine which AXI4 interfaces are associated with which clock domains, so that we can add the appropriate Vivado annotations to the top-level ports.
   * If not overridden, this will default to the mem, mmio, and l2 frontend buses
   *
   * @throws VivadoDesignException if a bus's node port count does not match its bundles, or a node is neither master nor slave
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
            throw new VivadoDesignException(s"AXI4 slave node for bus ${bus.name} has ${node.portParams.size} ports, but ${axis.size} AXI4 bundles were found. This is not supported.")
          axis.zip(node.portParams).map { case (axi, params: AXI4SlavePortParameters) => AXI4BusInfo(bus, AXIMM(axi), axi, Left(params)) }
        case node: AXI4MasterNode =>
          if (node.portParams.size != axis.size)
            throw new VivadoDesignException(s"AXI4 master node for bus ${bus.name} has ${node.portParams.size} ports, but ${axis.size} AXI4 bundles were found. This is not supported.")
          axis.zip(node.portParams).map { case (axi, params: AXI4MasterPortParameters) => AXI4BusInfo(bus, AXIMM(axi), axi, Right(params)) }
        case _ => throw new VivadoDesignException(s"AXI4 node for bus ${bus.name} is neither a master nor a slave node, but ${n.getClass.getName}. This is not supported.")
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
   * @return the frequency, or None if no associated bus declares one
   */
  def freqMapping(cb: ClockBundle, desc: ClkDesc): Option[Freq] = {
    if (desc.freq.isDefined) {
      soct.log.debug(s"Using explicitly defined frequency ${desc.freq.get} for clock bundle $cb")
      return desc.freq
    }
    val freqs = desc.buses.flatMap(_.dtsFrequency).distinct.map(hz => Freq(hz.toDouble))
    if (freqs.size > 1) {
      soct.log.warn(s"Multiple frequencies ${freqs.mkString(", ")} found for clock bundle $cb based on associated buses ${desc.buses.map(_.name).mkString(", ")}. This may result in incorrect Vivado annotations. Using the first frequency ${freqs.head} found.")
    }
    if (freqs.isEmpty) {
      soct.log.warn(s"No frequency information found for clock bundle $cb based on associated buses ${desc.buses.map(_.name).mkString(", ")}. This may result in incorrect Vivado annotations.")
    }
    freqs.headOption
  }

  /**
   * Description of every top-level clock bundle: its clock/reset pins, associated AXI4
   * interfaces, driven buses and derived frequency.
   *
   * @throws VivadoDesignException if an AXI4 interface's bus has no clock bundle
   */
  lazy val ioClocksMapping: Map[ClockBundle, ClkDesc] = {
    // The AXI4 interfaces associated with each clock bundle, if any. We use this information to add the appropriate Vivado annotations to the top-level ports.
    val axi4IfByClock = axi4BusMapping.flatten.map { assocBusIf =>
      val cb = s.clockBundleForBus(assocBusIf.bus)
        .getOrElse(throw new VivadoDesignException(s"Could not find clock bundle for bus ${assocBusIf.bus} associated with AXI4 interface ${assocBusIf.bdPin}"))
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
        cb -> desc.copy(freq = freqMapping(cb, desc))
        // Insertion-ordered map: ClockBundle keys hash by identity, so a plain HashMap would
        // iterate in a different order every JVM run and make the emitted TCL nondeterministic.
    }.to(SeqMap)
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