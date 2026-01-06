package soct.xilinx.components

import freechips.rocketchip.subsystem.ExtMem
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImpLike}
import soct.xilinx.SOCTVivado.{DEFAULT_MEMORY_ADDR_32, DEFAULT_MEMORY_ADDR_64}




object DDR4 extends XilinxIPComponent
  with OutputComponent
  with RequiresInputComponents
  with RequiresBoardOutputPins {


  val partName: String = "xilinx.com:ip:ddr4:2.2xilinx.com:ip:ddr4:2.2"

  def add()(implicit p: Parameters): Unit = {
    // require an ExtMem with DEFAULT_MEMORY_ADDR
    val extMemOpt = p(ExtMem)
    require(extMemOpt.isDefined, "Adding DDR4 requires ExtMem to be defined in the Parameters")
    val extMem = extMemOpt.get
    val memOffset = extMem.master.base
    require(memOffset == DEFAULT_MEMORY_ADDR_64 || memOffset == DEFAULT_MEMORY_ADDR_32, s"DDR4 requires ExtMem base address to be either 0x${DEFAULT_MEMORY_ADDR_64}%x or 0x${DEFAULT_MEMORY_ADDR_32}%x, got 0x${memOffset}%x")
  }

  val inputComponents: Seq[OutputComponent] = Seq()

  val boardOutputPinNames: Seq[String] = Seq()


}

