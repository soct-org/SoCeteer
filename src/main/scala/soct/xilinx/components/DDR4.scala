package soct.xilinx.components

import freechips.rocketchip.subsystem.{CanHaveMasterAXI4MemPort, ExtMem}
import org.chipsalliance.cde.config.Parameters
import soct.{ChiselTop, HasXilinxFPGA}
import soct.xilinx.SOCTVivado.{DEFAULT_MEMORY_ADDR_32, DEFAULT_MEMORY_ADDR_64}
import soct.xilinx.XilinxDesignException


class DDR4BdIntfPort extends XilinxIPComponent with XilinxBdIntfPort {
  lazy val INTERFACE_NAME = "ddr4_sdram"

  val mode: String = "Master"

  override val partName: String = "xilinx.com:interface:ddr4_rtl:1.0"
}


class DDR4 extends XilinxIPComponent with InstantiableComponent {
  val partName: String = "xilinx.com:ip:ddr4:2.2xilinx.com:ip:ddr4:2.2"

  override def checkAvailable(top: ChiselTop)(implicit p: Parameters): Unit = {
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

    val fpgaOpt = p(HasXilinxFPGA)
    if (fpgaOpt.isEmpty) {
      throw XilinxDesignException("Adding DDR4 requires the design to run on a Xilinx FPGA")
    }

    if (!fpgaOpt.get.hasDDR4) {
      throw XilinxDesignException(s"Adding DDR4 requires the target FPGA (${fpgaOpt.get.friendlyName}) to have DDR4 support")
    }

    val hasMemPort = top.fold(
      _   => false, // RawModule path cannot mix in CanHaveMasterAXI4MemPort
      cls => classOf[CanHaveMasterAXI4MemPort].isAssignableFrom(cls)
    )

    if (!hasMemPort)
      throw XilinxDesignException("Top must mix in CanHaveMasterAXI4MemPort")
  }


  override def connectToBoardInterface(intf: XilinxBdIntfPort): Unit = {
    intf match {
      case intf: DDR4BdIntfPort =>
        properties.update("CONFIG.C0_DDR4_BOARD_INTERFACE", intf.INTERFACE_NAME)
      case _ =>
        throw XilinxDesignException(s"$friendlyName can not connect to interface ${intf.INTERFACE_NAME}")
    }
  }
}

