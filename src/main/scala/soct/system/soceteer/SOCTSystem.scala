package soct.system.soceteer

import chisel3._
import chisel3.util.log2Ceil
import freechips.rocketchip.devices.debug.HasPeripheryDebug
import freechips.rocketchip.devices.tilelink.{BootROMLocated, BootROMParams, CanHavePeripheryCLINT, CanHavePeripheryPLIC, TLROM}
import freechips.rocketchip.diplomacy.AddressRange
import freechips.rocketchip.resources.{AddressMapEntry, DTSCompat, DTSModel, DTSTimebase, Resource, ResourceAnchors, ResourceBinding, ResourceInt, ResourceString}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink.TLFragmenter
import freechips.rocketchip.util.{DontTouch, ElaborationArtefacts, HasCoreMonitorBundles}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.bundlebridge.BundleBridgeSource
import org.chipsalliance.diplomacy.lazymodule.{InModuleBody, LazyModule, LazyRawModuleImp}
import soct.SOCTUtils.runCMakeCommand
import soct.{DTSCMakeGenerator, HasSOCTConfig, HasSOCTPaths, SOCTPaths, log}

import java.nio.file.Files

/**
 * Singleton object to hold the last instantiated RocketSystem for access by other components
 * This is mainly due to Chisel limitations and unstable API for inspecting an elaborated design
 */
object LastRocketSystem {
  var instance: Option[SOCTSystem] = None
}


class SOCTSystem(implicit p: Parameters) extends BaseSubsystem
  with HasAsyncExtInterrupts
  with HasExtInterrupts
  with CanHaveMasterAXI4MemPort
  with CanHaveMasterAXI4MMIOPort
  with CanHaveSlaveAXI4Port
  with InstantiatesHierarchicalElements
  with HasTileNotificationSinks
  with HasTileInputConstants
  with CanHavePeripheryCLINT
  with CanHavePeripheryPLIC
  with HasPeripheryDebug
  with HasHierarchicalElementsRootContext
  with HasHierarchicalElements
  with HasCoreMonitorBundles
  with HasRocketTiles
  with HasConfigurablePRCILocations // TODO Refactor this
{
  p(BootROMLocated(location)).foreach {
    SOCTBootROM.attach(_, this, CBUS)
  }
  override lazy val module = new SOCTSystemModuleImp(this)
  LastRocketSystem.instance = Some(this)
}

class SOCTSystemModuleImp[+L <: SOCTSystem](_outer: L) extends BaseSubsystemModuleImp(_outer)
  with HasHierarchicalElementsRootContextModuleImp
  with HasRTCModuleImp
  with HasExtInterruptsModuleImp
  with DontTouch {
  override lazy val outer = _outer
}

/** Base Subsystem class with no peripheral devices, ports or cores added yet */
abstract class BaseSubsystem(val location: HierarchicalLocation = InSubsystem)
                            (implicit p: Parameters) extends LazyModule
  with HasDTS
  with Attachable
  with HasConfigurableTLNetworkTopology {
  override val module: BaseSubsystemModuleImp[BaseSubsystem]

  val busContextName = "subsystem"

  viewpointBus.clockGroupNode := allClockGroupsNode

  // TODO: Preserve legacy implicit-clock behavior for IBUS for now. If binding a PLIC to the CBUS, ensure it is synchronously coupled to the SBUS.
  ibus.clockNode := viewpointBus.fixedClockNode

  // Collect information for use in DTS
  ResourceBinding {
    val managers = topManagers
    val max = managers.flatMap(_.address).map(_.max).max
    val width = ResourceInt((log2Ceil(max) + 31) / 32)
    val model = p(DTSModel)
    val compat = p(DTSCompat)
    val hertz = p(DTSTimebase) // add for timebase-frequency
    val devCompat = (model +: compat).map(s => ResourceString(s + "-dev"))
    val socCompat = (model +: compat).map(s => ResourceString(s + "-soc"))
    devCompat.foreach {
      Resource(ResourceAnchors.root, "compat").bind(_)
    }
    socCompat.foreach {
      Resource(ResourceAnchors.soc, "compat").bind(_)
    }
    Resource(ResourceAnchors.root, "model").bind(ResourceString(model))
    Resource(ResourceAnchors.root, "width").bind(width)
    Resource(ResourceAnchors.soc, "width").bind(width)
    Resource(ResourceAnchors.cpus, "width").bind(ResourceInt(1))
    Resource(ResourceAnchors.cpus, "hertz").bind(ResourceInt(hertz))

    managers.foreach { manager =>
      val value = manager.toResource
      manager.resources.foreach(resource =>
        resource.bind(value))
    }
  }
}

abstract class BaseSubsystemModuleImp[+L <: BaseSubsystem](_outer: L) extends LazyRawModuleImp(_outer) with HasDTSImp[L] {
  def dtsLM: L = _outer

  private val mapping: Seq[AddressMapEntry] = {
    dtsLM.collectResourceAddresses.groupBy(_._2).toList.flatMap { case (key, seq) =>
      AddressRange.fromSets(key.address).map { r => AddressMapEntry(r, key.permissions, seq.map(_._1)) }
    }.sortBy(_.range)
  }

  soct.log.debug("Generated Address Map")
  mapping.foreach(entry => soct.log.debug(entry.toString((dtsLM.tlBusWrapperLocationMap(p(TLManagerViewpointLocated(dtsLM.location))).busView.bundle.addressBits - 1) / 4 + 1)))


  ElaborationArtefacts.add("memmap.json", s"""{"mapping":[${mapping.map(_.toJSON).mkString(",")}]}""")

  // Confirm that all memory was described by DTS
  private val dtsRanges = AddressRange.unify(mapping.map(_.range))
  private val allRanges = AddressRange.unify(dtsLM.topManagers.flatMap { m => AddressRange.fromSets(m.address) })

  if (dtsRanges != allRanges) {
    soct.log.warn("Address map described by DTS differs from physical implementation:")
    AddressRange.subtract(allRanges, dtsRanges).foreach(r => soct.log.warn(s"\texists, but undescribed by DTS: $r"))
    AddressRange.subtract(dtsRanges, allRanges).foreach(r => soct.log.warn(s"\tdoes not exist, but described by DTS: $r"))
  }
}


object SOCTBootROM {
  /**
   * SOCTBootROM ignores most of the params - it compiles the bootrom image when invoked.
   */
  def attach(params: BootROMParams, subsystem: BaseSubsystem with HasHierarchicalElements with HasTileInputConstants, where: TLBusWrapperLocation)
            (implicit p: Parameters): TLROM = {
    val tlbus = subsystem.locateTLBusWrapper(where)
    val bootROMDomainWrapper = tlbus.generateSynchronousDomain(params.name).suggestName(s"${params.name}_domain")
    val bootROMResetVectorSourceNode = BundleBridgeSource[UInt]()

    // Function to build the bootrom and return its contents - invoked during LazyModule instantiation
    def getContents: Array[Byte] = {
      val config = p(HasSOCTConfig)
      val paths = p(HasSOCTPaths)

      // Write the device tree for this subsystem - create parent directories if needed
      Files.createDirectories(paths.dtsFile.getParent)
      Files.write(paths.dtsFile, subsystem.dts.getBytes())

      // Write a CMake file with important information from the DTS - simplifies building binaries for the system
      val soctCmake = DTSCMakeGenerator.generate(paths, config)
      Files.write(paths.soctSystemCMakeFile, soctCmake.getBytes)

      // Compile bootrom using CMake
      val defs = Map(
        "SOCT_SYSTEM_CMAKE" -> paths.soctSystemCMakeFile.toString,
        // And configure for bootrom build
        "BOOTROM_MODE" -> "ON",
      )

      val sourceDir = SOCTPaths.get("binaries")
      val buildDir = SOCTPaths.get("bootrom-build")
      // Delete the cache to force reconfiguration
      val cacheFile = buildDir.resolve("CMakeCache.txt")
      cacheFile.toFile.delete()
      val target = config.args.userBootrom.getOrElse(config.args.target.defaultBootrom)

      runCMakeCommand(Seq("-S", sourceDir.toString, "-B", buildDir.toString), defs)
      runCMakeCommand(Seq("--build", buildDir.toString, "--target", target), Map.empty)

      assert(Files.exists(paths.bootromImgFile), s"Bootrom image file ${paths.bootromImgFile} was not created")

      log.info("Building bootrom using CMake")

      val contents = Files.readAllBytes(paths.bootromImgFile)
      contents
    }

    val bootrom = bootROMDomainWrapper {
      LazyModule(new TLROM(params.address, params.size, getContents.toIndexedSeq, true, tlbus.beatBytes))
    }

    bootrom.node := tlbus.coupleTo(params.name) {
      TLFragmenter(tlbus, Some(params.name)) := _
    }
    // Drive the `subsystem` reset vector to the `hang` address of this Boot ROM.
    if (params.driveResetVector) {
      subsystem.tileResetVectorNexusNode := bootROMResetVectorSourceNode
      InModuleBody {
        val reset_vector_source = bootROMResetVectorSourceNode.bundle
        require(reset_vector_source.getWidth >= params.hang.bitLength,
          s"BootROM defined with a reset vector (${params.hang})too large for physical address space (${reset_vector_source.getWidth})")
        bootROMResetVectorSourceNode.bundle := params.hang.U
      }
    }
    bootrom
  }
}