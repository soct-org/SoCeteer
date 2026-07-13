package soct.system.vivado.components

import freechips.rocketchip.subsystem.ExtMem
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.abstracts._
import soct.system.vivado.misc.AXI4BusInfo
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands, VivadoDesignException}

import java.nio.file.{Files, Path}

/**
 * AXI4 address deinterleaver for one memory channel.
 *
 * When RocketChip is configured with multiple memory channels, the system bus
 * distributes cache lines round-robin across the channels: each channel's
 * exported [[freechips.rocketchip.diplomacy.AddressSet]]s pin the channel-select
 * bits (e.g. `AddressSet(0x80000040, 0x7fffffbf)` pins bit 6 to 1), so the
 * addresses arriving at the channel are sparse - every other cache line is a
 * hole. A DDR controller fed with these raw addresses would waste half (or
 * 1-1/N) of its capacity and may mis-decode row/bank/column bits.
 *
 * This component wraps the `axi_addr_deinterleaver` Verilog module, a purely
 * combinational AXI4 pass-through that removes the constant channel-select bits
 * from AW/AR addresses and shifts the upper bits down, mapping the sparse view
 * onto the dense range `[base, base + capacity)`. It sits between the
 * RocketSystem's memory AXI port and the memory SmartConnect, so it runs
 * entirely in the core clock domain and all interface widths are known at
 * generation time.
 *
 * Use [[AXIAddrDeinterleaver.fromBusInfo]] to construct it: the interleave
 * geometry is derived from the diplomacy address sets, and `None` is returned
 * for contiguous (non-interleaved) channels where no deinterleaving is needed.
 */
case class AXIAddrDeinterleaver(mAxi: AXI4BusInfo, geometry: AXIAddrDeinterleaver.InterleaveGeometry)
                               (implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with IsModule with ConnectOps with HasBdAddr {

  override def reference: String = "axi_addr_deinterleaver" // The module name inside the collateral file - DO NOT CHANGE

  /** Clock input - carries no logic, only associates both AXI interfaces with a clock domain for Vivado */
  object ACLK extends BdPinIn("aclk", AXIAddrDeinterleaver.this)

  /** Sparse (interleaved) side - connect to the RocketSystem memory AXI port */
  object S_AXI extends BdIntfPin("S_AXI", AXIAddrDeinterleaver.this)

  /** Dense (contiguous) side - connect towards the DDR controller (typically via the memory SmartConnect) */
  object M_AXI extends BdIntfPin("M_AXI", AXIAddrDeinterleaver.this)

  private def bundleParams = mAxi.axiBundle.params

  /**
   * Copy the `axi_addr_deinterleaver.v` collateral from the classpath resources next to the
   * design sources.
   *
   * @throws soct.system.vivado.VivadoDesignException if the bundled Verilog resource is missing
   */
  override def dumpCollaterals(outDir: Path, dirName: Option[String] = None): Option[Path] = {
    val dest = super.dumpCollaterals(outDir, dirName = Some(friendlyName)).get
    val file = "axi_addr_deinterleaver.v"
    val contentOpt = soct.getResource(s"/deinterleaver/$file")
    if (contentOpt.isEmpty) {
      throw VivadoDesignException(s"Could not find AXIAddrDeinterleaver collateral file: $file")
    }
    Files.write(dest.resolve(file), contentOpt.get.getBytes)
    Some(dest)
  }

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.ADDR_WIDTH" -> bundleParams.addrBits.toString,
    "CONFIG.DATA_WIDTH" -> bundleParams.dataBits.toString,
    "CONFIG.ID_WIDTH" -> bundleParams.idBits.toString,
    "CONFIG.DROP_LSB" -> geometry.dropLsb.toString,
    "CONFIG.DROP_BITS" -> geometry.dropBits.toString,
    "CONFIG.BASE" -> s"0x${geometry.base.toString(16).toUpperCase}"
  )

  override def assignAddrTcl: TCLCommands = {
    // The S_AXI segment must cover every address the master can emit: the
    // sparse view spans the whole (interleaved) memory region, so map the full
    // 2^addrBits address space of the master onto our reg0 segment. The dense
    // side (M_AXI -> DDR4) is assigned by the DDR4 component itself.
    val range = BigInt(1) << bundleParams.addrBits
    Seq(
      s"assign_bd_address -offset 0x0 -range 0x${range.toString(16).toUpperCase} -target_address_space [get_bd_addr_spaces ${mAxi.bdPin.ref}] [get_bd_addr_segs ${S_AXI.ref}/reg0]".tcl
    )
  }
}


object AXIAddrDeinterleaver {

  /**
   * Interleave geometry of one memory channel, derived from its diplomacy address sets.
   *
   * @param dropLsb      Index of the lowest channel-select bit (log2 of the interleave block size, e.g. 6 for 64-byte cache lines)
   * @param dropBits     Number of channel-select bits (log2 of the number of memory channels)
   * @param channelIndex The value of the channel-select bits for this channel
   * @param base         Base address of the interleaved memory region (start of DRAM as seen by the cores)
   */
  case class InterleaveGeometry(dropLsb: Int, dropBits: Int, channelIndex: Int, base: BigInt) {
    def nChannels: Int = 1 << dropBits

    def blockBytes: BigInt = BigInt(1) << dropLsb

    override def toString: String =
      s"InterleaveGeometry(blockBytes=0x${blockBytes.toString(16)}, nChannels=$nChannels, channelIndex=$channelIndex, base=0x${base.toString(16)})"
  }

  /**
   * Derive the interleave geometry of a memory channel from its exported AXI4 slave address sets.
   *
   * RocketChip builds each channel's address filter as
   * `AddressSet(channel * blockBytes, ~((nChannels-1) * blockBytes))`, so the sets share a mask
   * with a single run of zero bits (the channel-select bits) between the low block-offset bits and
   * the high span bits, e.g. `0x7fffffbf` = 2 GiB span with bit 6 pinned.
   *
   * @param mAxi the memory-channel AXI4 port exported by the RocketSystem
   * @param base base address of the interleaved memory region (start of DRAM as seen by the cores)
   * @return `None` if the address sets are contiguous (single channel / no interleaving),
   *         `Some(geometry)` if they follow the round-robin interleave pattern
   * @throws VivadoDesignException if the address sets have a hole pattern this component cannot compact
   */
  def geometryOf(mAxi: AXI4BusInfo, base: BigInt): Option[InterleaveGeometry] = {
    val slaves = mAxi.axiParams.fold(
      sp => sp.slaves,
      _ => throw VivadoDesignException("AXIAddrDeinterleaver requires an AXI4 slave port (a memory channel), but got a master port.")
    )
    if (slaves.length != 1) {
      throw VivadoDesignException(s"AXIAddrDeinterleaver expects exactly one AXI4 slave per memory channel, but found ${slaves.length}.")
    }
    val sets = slaves.head.address
    val mask = sets.head.mask
    if (!sets.forall(_.mask == mask)) {
      throw VivadoDesignException(s"Memory channel address sets have differing masks, cannot derive interleave geometry: ${sets.mkString(", ")}")
    }

    // The lowest zero bit of the mask marks the end of the block-offset bits...
    val dropLsb = (~mask).lowestSetBit
    // ...and the length of the zero-run starting there is the number of channel-select bits.
    val rest = mask >> dropLsb
    if (rest == 0 || rest.lowestSetBit == 0) {
      // Mask is contiguous ones - a plain contiguous address range, nothing to deinterleave.
      return None
    }
    val dropBits = rest.lowestSetBit

    // Reject anything but a single zero-run (one hole) - other patterns are not a
    // round-robin channel interleave and cannot be compacted by a bit-drop.
    val filled = mask | (((BigInt(1) << dropBits) - 1) << dropLsb)
    if ((filled & (filled + 1)) != 0) {
      throw VivadoDesignException(s"Memory channel mask 0x${mask.toString(16)} has more than one hole; unsupported interleave pattern.")
    }
    if (dropLsb < 1) {
      throw VivadoDesignException("Interleave block size below 2 bytes is not supported by axi_addr_deinterleaver.")
    }
    if (base % (BigInt(1) << (dropLsb + dropBits)) != 0) {
      throw VivadoDesignException(s"Memory base address 0x${base.toString(16)} must be aligned to the interleave granule (${1 << (dropLsb + dropBits)} bytes).")
    }
    if (!sets.forall(_.base >= base)) {
      throw VivadoDesignException(s"Memory channel address sets ${sets.mkString(", ")} start below the memory base address 0x${base.toString(16)}.")
    }

    val chMask = (BigInt(1) << dropBits) - 1
    val indices = sets.map(s => ((s.base >> dropLsb) & chMask).toInt).distinct
    if (indices.length != 1) {
      throw VivadoDesignException(s"Memory channel address sets encode inconsistent channel indices ${indices.mkString(", ")}: ${sets.mkString(", ")}")
    }

    Some(InterleaveGeometry(dropLsb, dropBits, indices.head, base))
  }

  /**
   * Create a deinterleaver for the given memory-channel AXI4 port, if its address sets are interleaved.
   *
   * @param mAxi the memory-channel AXI4 port exported by the RocketSystem
   * @return `None` when the channel covers a contiguous range (no deinterleaver needed)
   * @throws soct.system.vivado.VivadoDesignException if ExtMem is not defined in the parameters,
   *                                                  or the address sets have an unsupported hole pattern (see [[geometryOf]])
   */
  def fromBusInfo(mAxi: AXI4BusInfo)(implicit bd: SOCTBdBuilder, p: Parameters): Option[AXIAddrDeinterleaver] = {
    val base = p(ExtMem).getOrElse(
      throw VivadoDesignException("AXIAddrDeinterleaver requires ExtMem to be defined in parameters.")
    ).master.base
    geometryOf(mAxi, base).map(geo => AXIAddrDeinterleaver(mAxi, geo))
  }
}
