package soct

import chisel3._
import freechips.rocketchip.devices.debug.Debug
import freechips.rocketchip.devices.tilelink.{BootROM, BootROMLocated}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.system.SimAXIMem
import freechips.rocketchip.util.{AsyncResetReg, DontTouch}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.LazyModule

class RocketSystem(implicit p: Parameters) extends RocketSubsystem
  with HasAsyncExtInterrupts
  with CanHaveMasterAXI4MemPort
  with CanHaveMasterAXI4MMIOPort
  with CanHaveSlaveAXI4Port {
  p(BootROMLocated(location)).foreach {
    BootROM.attach(_, this, CBUS)
  }
  override lazy val module = new RocketSystemModuleImp(this)
}

class RocketSystemModuleImp[+L <: RocketSystem](_outer: L) extends RocketSubsystemModuleImp(_outer)
  with HasRTCModuleImp
  with HasExtInterruptsModuleImp
  with DontTouch


/**
 * Top-level module for synthesis of the RocketSystem within SOCT
 */
class SOCTSynTop(implicit p: Parameters) extends RocketSystem



/**
 * Top-level module for simulation of the RocketSystem within SOCT
 */
class SOCTSimTop()(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val success = Output(Bool())
  })
  val ldut = LazyModule(new RocketSystem)
  val dut = ldut.module

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