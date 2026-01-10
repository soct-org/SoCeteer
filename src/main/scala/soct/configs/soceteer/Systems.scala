package soct

import chisel3._
import chisel3.reflect.DataMirror
import freechips.rocketchip.devices.debug.Debug
import freechips.rocketchip.devices.tilelink.{BootROMLocated, BootROMParams, TLROM}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.system.SimAXIMem
import freechips.rocketchip.tilelink.TLFragmenter
import freechips.rocketchip.util.{AsyncResetReg, DontTouch}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.bundlebridge.BundleBridgeSource
import org.chipsalliance.diplomacy.lazymodule.{InModuleBody, LazyModule}
import soct.SOCTUtils.runCMakeCommand
import soct.xilinx.{BDBuilder, SOCTVivado}
import soct.xilinx.components._

import java.nio.file.Files

/**
 * Singleton object to hold the last instantiated RocketSystem for access by other components
 * This is mainly due to Chisel limitations and unstable API for inspecting an elaborated design
 */
object LastRocketSystem {
  var instance: Option[RocketSystem] = None
}


class RocketSystem(implicit p: Parameters) extends RocketSubsystem
  with HasAsyncExtInterrupts
  with CanHaveMasterAXI4MemPort
  with CanHaveMasterAXI4MMIOPort
  with CanHaveSlaveAXI4Port {
  p(BootROMLocated(location)).foreach {
    SOCTBootROM.attach(_, this, CBUS)
  }
  override lazy val module = new RocketSystemModuleImp(this)
  LastRocketSystem.instance = Some(this)
}

class RocketSystemModuleImp[+L <: RocketSystem](_outer: L) extends RocketSubsystemModuleImp(_outer)
  with HasRTCModuleImp
  with HasExtInterruptsModuleImp
  with DontTouch


/**
 * Top-level module for Yosys synthesis of the RocketSystem within SOCT
 */
class SOCTYosysTop(implicit p: Parameters) extends RocketSystem {

}

/**
 * Top-level module for synthesis of the RocketSystem within SOCT
 */
class SOCTSynTop(implicit p: Parameters) extends RocketSystem {
  implicit val top: ChiselTop = Right(this.getClass)
  implicit val bd: BDBuilder = new BDBuilder

  // InModuleBody is needed to ensure this code doesn't run before the LazyModule is fully constructed
  InModuleBody {
    if (p(HasDDR4ExtMem).isDefined) {
      DDR4(
        ddr4Idx = p(HasDDR4ExtMem).get,
        intf = DDR4BdIntfPort()
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

    val tcl = bd.generateTcl()

    println(tcl)
  }


}


/**
 * Top-level module for simulation of the RocketSystem within SOCT
 */
class SOCTSimTop()(implicit p: Parameters) extends Module {
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