package soct.xilinx.components

import freechips.rocketchip.subsystem.ExtMem
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImpLike}
import soct.RunsOnXilinxFPGA
import soct.xilinx.SOCTVivado.{DEFAULT_MEMORY_ADDR_32, DEFAULT_MEMORY_ADDR_64}
import soct.xilinx.XilinxDesignException


class DDR4BoardInterface extends XilinxIPComponent with XilinxBoardInterface {
  lazy val INTERFACE_NAME = "ddr4_sdram"

  val mode: String = "Master"

  override val partName: String = "xilinx.com:interface:ddr4_rtl:1.0"
}


class DDR4 extends XilinxIPComponent with Instantiable {
  val partName: String = "xilinx.com:ip:ddr4:2.2xilinx.com:ip:ddr4:2.2"

  override def checkAvailable()(implicit p: Parameters): Unit = {
    val extMemOpt = p(ExtMem)
    if (extMemOpt.isEmpty) {
      throw XilinxDesignException("Adding DDR4 requires ExtMem to be defined in the Parameters")
    }

    val extMem = extMemOpt.get
    val memOffset = extMem.master.base
    if (!(memOffset == DEFAULT_MEMORY_ADDR_64 || memOffset == DEFAULT_MEMORY_ADDR_32)) {
      throw XilinxDesignException(
        s"Adding DDR4 requires ExtMem base address to be either 0x${DEFAULT_MEMORY_ADDR_64}%x or 0x${DEFAULT_MEMORY_ADDR_32}%x, got 0x${memOffset}%x"
      )
    }

    val fpgaOpt = p(RunsOnXilinxFPGA)
    if (fpgaOpt.isEmpty) {
      throw XilinxDesignException("Adding DDR4 requires the design to run on a Xilinx FPGA")
    }

    if (!fpgaOpt.get.hasDDR4) {
      throw XilinxDesignException(s"Adding DDR4 requires the target FPGA (${fpgaOpt.get.friendlyName}) to have DDR4 support")
    }
  }


  override def connectToBoardInterface(intf: XilinxBoardInterface): Unit = {
    intf match {
      case intf: DDR4BoardInterface =>
        properties.update("CONFIG.C0_DDR4_BOARD_INTERFACE", intf.INTERFACE_NAME)
      case _ =>
        throw XilinxDesignException(s"$friendlyName can not connect to interface ${intf.INTERFACE_NAME}")
    }
  }
}

