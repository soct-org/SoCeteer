package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.abstracts._
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands, VivadoDesignException}

import java.nio.file.{Files, Path}

/**
 * Combinational AXI4 pass-through that adds a constant offset to every AW/AR address, mapping
 * a window of the master's address space onto a slave with fixed high addresses.
 *
 * Used to reach the ZynqMP PS register space (DP controller etc. at 0xFDxx_xxxx behind
 * S_AXI_LPD) from the Rocket MMIO port, whose decode window cannot cover those addresses
 * without overlapping the DRAM decode. Wraps the `axi_addr_offset` Verilog collateral.
 *
 * @param getAxiMasterPin the master whose address space gets the window (the Rocket MMIO port)
 * @param windowBase      window base address in the master's address space (e.g. 0x7D00_0000)
 * @param windowSize      window size in bytes (power of two)
 * @param targetBase      base address of the target region behind the slave (e.g. 0xFD00_0000)
 */
case class AxiAddrOffset(getAxiMasterPin: BdIntfPin, windowBase: BigInt, windowSize: BigInt, targetBase: BigInt)
                        (implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with IsModule with ConnectOps with HasBdAddr {

  if ((windowSize & (windowSize - 1)) != 0 || windowSize <= 0) {
    throw VivadoDesignException(s"AxiAddrOffset window size must be a positive power of two, got 0x${windowSize.toString(16)}")
  }
  if (windowBase % windowSize != 0 || targetBase % windowSize != 0) {
    throw VivadoDesignException(s"AxiAddrOffset window (0x${windowBase.toString(16)}) and target (0x${targetBase.toString(16)}) bases must be aligned to the window size (0x${windowSize.toString(16)})")
  }

  override def reference: String = "axi_addr_offset" // The module name inside the collateral file - DO NOT CHANGE

  /** Clock input - carries no logic, only associates both AXI interfaces with a clock domain for Vivado */
  object ACLK extends BdPinIn("aclk", AxiAddrOffset.this)

  /** Window side - connect towards the master's interconnect */
  object S_AXI extends BdIntfPin("S_AXI", AxiAddrOffset.this)

  /** Target side - connect to the fixed-address slave */
  object M_AXI extends BdIntfPin("M_AXI", AxiAddrOffset.this)

  /**
   * Copy the `axi_addr_offset.v` collateral from the classpath resources next to the design sources.
   *
   * @throws soct.system.vivado.VivadoDesignException if the bundled Verilog resource is missing
   */
  override def dumpCollaterals(outDir: Path, dirName: Option[String] = None): Option[Path] = {
    val dest = super.dumpCollaterals(outDir, dirName = Some(friendlyName)).get
    val file = "axi_addr_offset.v"
    val contentOpt = soct.getResource(s"/addr_offset/$file")
    if (contentOpt.isEmpty) {
      throw VivadoDesignException(s"Could not find AxiAddrOffset collateral file: $file")
    }
    Files.write(dest.resolve(file), contentOpt.get.getBytes)
    Some(dest)
  }

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.ADDR_WIDTH" -> "32",
    "CONFIG.DATA_WIDTH" -> "32",
    "CONFIG.ID_WIDTH" -> "1",
    "CONFIG.OFFSET" -> s"0x${(targetBase - windowBase).toString(16).toUpperCase}"
  )

  /**
   * Map the window into the master's address space, and the target's fixed segments into the
   * M_AXI address space.
   */
  override def assignAddrTcl: TCLCommands = Seq(
    s"assign_bd_address -offset 0x${windowBase.toString(16).toUpperCase} -range 0x${windowSize.toString(16).toUpperCase} -target_address_space [get_bd_addr_spaces ${getAxiMasterPin.ref}] [get_bd_addr_segs ${S_AXI.ref}/reg0]".tcl,
  )
}
