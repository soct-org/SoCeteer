package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.abstracts._
import soct.system.vivado.misc.DTSInfo
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands, VivadoDesignException}

/**
 * Xilinx AXI GPIO as a read-only status port: software reads fabric signals (e.g. lock and
 * error flags of the video pipeline) that have no register interface of their own.
 * Channel 1 data is at register offset 0x0, the optional channel 2 at 0x8.
 * Documentation: https://docs.amd.com/r/en-US/pg144-axi-gpio
 *
 * @param dtsInfo         device-tree description (control registers)
 * @param getAxiMasterPin the AXI master reaching the registers (the Rocket MMIO port)
 * @param ch1Width        bit width of input channel 1 (1 to 32)
 * @param ch2Width        bit width of the optional input channel 2 (1 to 32)
 */
case class AxiGpio(override val dtsInfo: DTSInfo, override val getAxiMasterPin: BdIntfPin,
                   ch1Width: Int, ch2Width: Option[Int] = None)
                  (implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with Xip with ConnectOps with HasAxiSlave with HasDTSInfo {

  if (ch1Width < 1 || ch1Width > 32 || ch2Width.exists(w => w < 1 || w > 32)) {
    throw VivadoDesignException(s"AxiGpio channel widths must be 1..32, got $ch1Width / $ch2Width")
  }

  override def partName: String = "xilinx.com:ip:axi_gpio:2.0"

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.C_ALL_INPUTS" -> "1",
    "CONFIG.C_GPIO_WIDTH" -> s"$ch1Width"
  ) ++ ch2Width.map(w => Map(
    "CONFIG.C_IS_DUAL" -> "1",
    "CONFIG.C_ALL_INPUTS_2" -> "1",
    "CONFIG.C_GPIO2_WIDTH" -> s"$w"
  )).getOrElse(Map.empty)

  /** Control registers (AXI4-Lite) */
  override lazy val S_AXI: BdIntfPin = new BdIntfPin("S_AXI", this)

  object S_AXI_ACLK extends BdPinIn("s_axi_aclk", AxiGpio.this)

  object S_AXI_ARESETN extends BdPinIn("s_axi_aresetn", AxiGpio.this)

  /** Channel 1 input (read at offset 0x0) */
  object GPIO_IO_I extends BdPinIn("gpio_io_i", AxiGpio.this)

  /** Channel 2 input (read at offset 0x8), only with [[ch2Width]] set */
  object GPIO2_IO_I extends BdPinIn("gpio2_io_i", AxiGpio.this)

  /**
   * @throws soct.system.vivado.VivadoDesignException if the DTS info does not carry exactly one register region
   */
  override def assignAddrTcl: TCLCommands = {
    val regs = dtsInfo.regs
    if (regs.size != 1) {
      throw VivadoDesignException(s"AxiGpio DTSInfo must have exactly one reg entry, but found ${regs.size}")
    }
    val (_, _offset, _size) = regs.head
    val offset = "0x%08X".format(_offset)
    val size = "0x%08X".format(_size)
    Seq(
      s"assign_bd_address -offset $offset -range $size -target_address_space [get_bd_addr_spaces ${getAxiMasterPin.ref}] [get_bd_addr_segs ${S_AXI.ref}/Reg]".tcl
    )
  }
}
