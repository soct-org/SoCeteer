package soct

import chisel3._
import chisel3.util.log2Ceil
import freechips.rocketchip.devices.debug.{Debug, HasPeripheryDebug}
import freechips.rocketchip.devices.tilelink.{BootROMLocated, BootROMParams, CanHavePeripheryCLINT, CanHavePeripheryPLIC, TLROM}
import freechips.rocketchip.diplomacy.AddressRange
import freechips.rocketchip.prci.ClockGroupNode
import freechips.rocketchip.resources.{AddressMapEntry, DTSCompat, DTSModel, DTSTimebase, Resource, ResourceAnchors, ResourceBinding, ResourceInt, ResourceString}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.system.SimAXIMem
import freechips.rocketchip.tilelink.TLFragmenter
import freechips.rocketchip.util.{AsyncResetReg, DontTouch, ElaborationArtefacts, HasCoreMonitorBundles}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.bundlebridge.BundleBridgeSource
import org.chipsalliance.diplomacy.lazymodule.{InModuleBody, LazyModule, LazyRawModuleImp}
import soct.SOCTUtils.runCMakeCommand
import soct.xilinx.{BDBuilder, XilinxDesignException}
import soct.xilinx.components._

import java.nio.file.Files

/**
 * Singleton object to hold the last instantiated RocketSystem for access by other components
 * This is mainly due to Chisel limitations and unstable API for inspecting an elaborated design
 */
object LastRocketSystem {
  var instance: Option[RocketSystem] = None
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

  println("Generated Address Map")
  mapping.foreach(entry => println(entry.toString((dtsLM.tlBusWrapperLocationMap(p(TLManagerViewpointLocated(dtsLM.location))).busView.bundle.addressBits - 1) / 4 + 1)))
  println("")

  ElaborationArtefacts.add("memmap.json", s"""{"mapping":[${mapping.map(_.toJSON).mkString(",")}]}""")

  // Confirm that all memory was described by DTS
  private val dtsRanges = AddressRange.unify(mapping.map(_.range))
  private val allRanges = AddressRange.unify(dtsLM.topManagers.flatMap { m => AddressRange.fromSets(m.address) })

  if (dtsRanges != allRanges) {
    println("Address map described by DTS differs from physical implementation:")
    AddressRange.subtract(allRanges, dtsRanges).foreach { case r =>
      println(s"\texists, but undescribed by DTS: ${r}")
    }
    AddressRange.subtract(dtsRanges, allRanges).foreach { case r =>
      println(s"\tdoes not exist, but described by DTS: ${r}")
    }
    println("")
  }
}

class RocketSystem(implicit p: Parameters) extends BaseSubsystem
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
  override lazy val module = new RocketSystemModuleImp(this)
  LastRocketSystem.instance = Some(this)
}

class RocketSystemModuleImp[+L <: RocketSystem](_outer: L) extends BaseSubsystemModuleImp(_outer)
  with HasHierarchicalElementsRootContextModuleImp
  with HasRTCModuleImp
  with HasExtInterruptsModuleImp
  with DontTouch {
  override lazy val outer = _outer
}


/**
 * Top-level module for Yosys synthesis of the RocketSystem within SOCT
 */
class SOCTYosysSystem(implicit p: Parameters) extends RocketSystem

/**
 * Top-level module for synthesis of the RocketSystem within SOCT using Vivado
 */
class SOCTVivadoSystem(implicit p: Parameters) extends RocketSystem {
  if (p(HasBdBuilder).isDefined) {
    implicit val bd: BDBuilder = p(HasBdBuilder).get

    // This is the top-level instance representing this system in the block design
    val topInstance: InstantiableBdComp with IsModule =
      new InstantiableBdComp with IsModule {
        private val c = p(HasSOCTConfig)

        override def reference: String = c.topModuleName

        override def friendlyName: String = SOCTVivadoSystem.this.instanceName

        override def instanceName: String = friendlyName

        override def connectTclCommands: Seq[String] = Seq.empty // Top module is only receiver of connections
      }

    bd.init(p, topInstance) // Register this top instance with the BDBuilder.


    InModuleBody {
      val dClock300 = DiffClockBdIntfPort(300.0)
      val coreCLock = ClockDomain("core", 100.0)
      val peripheryCD = ClockDomain("periphery", p(PeripheryClockFrequency))
      val axiInfts = Seq(mem_axi4, mmio_axi4, l2_frontend_bus_axi4).flatten

      axiInfts.foreach { axiInft =>
        AXIBdXInterface(axiInft)
      }

      if (p(HasDDR4ExtMem).isDefined) {
        DDR4(
          ddr4Idx = p(HasDDR4ExtMem).get,
          ddr4Intf = DDR4BdIntfPort(),
          clockIn = dClock300,
          clockOut = coreCLock
        )
      }

      if (p(HasSDCardPMOD).isDefined) {
        SDCardPMOD(
          pmodIdx = p(HasSDCardPMOD).get,
          cdPort = SDIOCDPort(),
          clkPort = SDIOClkPort(),
          cmdPort = SDIOCmdPort(),
          dataPort = SDIODataPort()
        )
      }

      if (debug.getWrappedValue.isDefined) {
        val debugIf = debug.getWrappedValue.get

        if (debugIf.systemjtag.isDefined) {
          val jtagIO = debugIf.systemjtag.get
          JTAGBdXInterface(jtagIO.jtag)
          // Tie off unused fields using inline constants - rename for clarity in block design
          new InlineConstant("b10010001001".U, jtagIO.mfr_id.getWidth, Seq(jtagIO.mfr_id))
          {override def friendlyName: String = "jtag_mfr_id_constant"}

          new InlineConstant(0.U, jtagIO.part_number.getWidth, Seq(jtagIO.part_number))
          {override def friendlyName: String = "jtag_part_number_constant"}

          new InlineConstant(0.U, jtagIO.version.getWidth, Seq(jtagIO.version))
          {override def friendlyName: String = "jtag_version_constant"}
        }
      }
    }
  } else {
    log.info("No BDBuilder found in parameters - skipping block design generation and only elaborating Chisel design. " +
      "If you intended to use Vivado, please ensure HasBdBuilder is set in the parameters.")
  }
}

/**
 * Top-level module for simulation of the RocketSystem within SOCT
 */
class SOCTSimSystem()(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val success = Output(Bool())
  })
  val ldut = LazyModule(new RocketSystem)
  val dut = Module(ldut.module)

  ldut.io_clocks.get.elements.values.foreach(_.clock := clock)
  // Allow the debug ndreset to reset the dut, but not until the initial reset has completed
  val dut_reset = (reset.asBool | ldut.debug.map { debug => AsyncResetReg(debug.ndreset) }.getOrElse(false.B)).asBool
  ldut.io_clocks.get.elements.values.foreach(_.reset := dut_reset)

  dut.dontTouchPorts()
  dut.tieOffInterrupts()
  SimAXIMem.connectMem(ldut)
  SimAXIMem.connectMMIO(ldut)
  ldut.l2_frontend_bus_axi4.foreach(a => {
    a.ar.valid := false.B
    a.ar.bits := DontCare
    a.aw.valid := false.B
    a.aw.bits := DontCare
    a.w.valid := false.B
    a.w.bits := DontCare
    a.r.ready := false.B
    a.b.ready := false.B
  })
  Debug.connectDebug(ldut.debug, ldut.resetctrl, ldut.psd, clock, reset.asBool, io.success)
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