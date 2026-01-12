package soct.xilinx.components

import freechips.rocketchip.subsystem.{CanHaveMasterAXI4MemPort, ExtMem}
import org.chipsalliance.cde.config.Parameters
import soct.{ChiselTop, HasXilinxFPGA}
import soct.xilinx.SOCTVivado.{DEFAULT_MEMORY_ADDR_32, DEFAULT_MEMORY_ADDR_64}
import soct.xilinx.{BDBuilder, XilinxDesignException}


case class DDR4BdIntfPort()(implicit bd: BDBuilder, p: Parameters, top: ChiselTop)
  extends XilinxBdIntfPort {
  override def INTERFACE_NAME = "ddr4_sdram"

  override def mode: String = "Master"

  override def partName: String = "xilinx.com:interface:ddr4_rtl:1.0"
}


case class DDR4(ddr4Idx: Int,
                intf: XilinxBdIntfPort)
               (implicit bd: BDBuilder, p: Parameters, top: ChiselTop)
  extends InstantiableBdComp with IsXilinxIP {

  override def partName: String = "xilinx.com:ip:ddr4:2.2"

  override def checkAvailable(): Unit = {
    super.checkAvailable()
    val fpga = p(HasXilinxFPGA).get

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

    if (!fpga.portsDDR4.contains(ddr4Idx))
      throw XilinxDesignException(s"DDR4 index $ddr4Idx is not available on the selected FPGA")

    // DDR4 requires a master AXI4 memory port
    val hasMemPort = top.fold(_ => false, cls => classOf[CanHaveMasterAXI4MemPort].isAssignableFrom(cls))
    if (!hasMemPort)
      throw XilinxDesignException("Top must mix in CanHaveMasterAXI4MemPort")
  }

  override def defaultProperties: Map[String, String] = {
    Map(
      "CONFIG.C0_DDR4_BOARD_INTERFACE" -> intf.INTERFACE_NAME
    )
  }
}

