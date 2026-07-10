package soct.system.vivado.fpga

import soct.SOCTBytes._

/**
 * Registry mapping DDR4 DIMM part names to their capacities.
 *
 * The Vivado part name passed via --ext-mem-part is the ground truth for how much memory a
 * design can address: the DDR4 IP sizes its address decode window from the selected part.
 * Deriving the capacity from the part keeps the ExtMem configuration, the generated DTB and
 * the controller's decode window consistent by construction.
 *
 * Resolution order:
 *   1. Exact match on the base part name (everything before the first '-', i.e. without the
 *      speed-grade suffix) against the registry below.
 *   2. The Micron module naming pattern: MTA<n>ATF<depth><width>... encodes <depth> x <width>
 *      organisation (e.g. MTA4ATF25664HZ = 256M x 64 = 2 GiB, MTA8ATF2G64HZ = 2G x 64 = 16 GiB).
 *      Only the 64-bit data bytes count towards capacity; ECC (x72) check bits are not addressable.
 *
 * Parts from other vendors (Samsung, SK hynix, ...) or non-standard names must be added to the
 * registry below.
 */
object PartRegistry {

  // TODO ADD YOUR DIMM HERE! - Keys are the base part name without the speed-grade suffix, uppercase
  private val registry: Map[String, Bytes] = Map(
    "MTA4ATF25664HZ" -> 2.GiB, // Micron 256M x 64 SODIMM (ZCU104 stock module)
    "MTA4ATF51264HZ" -> 4.GiB, // Micron 512M x 64 SODIMM
    "MTA8ATF1G64HZ" -> 8.GiB, // Micron 1G x 64 SODIMM
    "MTA8ATF2G64HZ" -> 16.GiB // Micron 2G x 64 SODIMM
  )

  /**
   * Micron MTA...ATF... module pattern: depth is either "<n>G" (G-locations) or a three-digit
   * M-location count (256, 512), followed by the data width (64, or 72 with ECC).
   */
  private val MicronATF = """(?i)MTA\d+ATF(?:(\d+)G|(\d{3}))(?:64|72).*""".r

  /** The part name without its speed-grade suffix, e.g. "MTA8ATF2G64HZ-3G2R1" -> "MTA8ATF2G64HZ" */
  private def basePart(part: String): String = part.trim.toUpperCase.takeWhile(_ != '-')

  /** Speed-grade token at the start of the suffix, e.g. "2G3" in "2G3H1" (2G1 = DDR4-2133, 2G3 = 2400, 2G6 = 2666, 3G2 = 3200) */
  private val SpeedGrade = """(\d[A-Z]\d).*""".r

  /**
   * The part name as the Vivado DDR4 IP catalog expects it: the physical module label and the
   * SPD carry a die/module revision after the speed grade (e.g. "MTA16ATF2G64HZ-2G3H1"), but the
   * catalog lists parts without it ("MTA16ATF2G64HZ-2G3"). Strips the revision code, keeping the
   * base part and the three-character speed grade.
   */
  def vivadoPartName(part: String): String = {
    val trimmed = part.trim.toUpperCase
    trimmed.split("-", 2) match {
      case Array(base, SpeedGrade(grade)) => s"$base-$grade"
      case _ => trimmed
    }
  }

  /**
   * Look up (or derive) the capacity of a DDR4 DIMM part.
   *
   * @param part The full Vivado part name, e.g. "MTA8ATF2G64HZ-3G2R1"
   * @return The usable capacity, or None if the part is unknown to both the registry and the
   *         vendor naming patterns
   */
  def capacityOf(part: String): Option[Bytes] = {
    registry.get(basePart(part)).orElse {
      basePart(part) match {
        case MicronATF(gLoc, _) if gLoc != null => Some(Bytes(gLoc.toLong * 8 * GiB)) // <n>G locations x 8 bytes
        case MicronATF(_, mLoc) if mLoc != null => Some(Bytes(mLoc.toLong * 8 * MiB)) // <n>M locations x 8 bytes
        case _ => None
      }
    }
  }

  /** Registered base part names, for error messages */
  def knownParts: Seq[String] = registry.keys.toSeq.sorted
}


/**
 * Registry for known FPGA boards.
 */
object FPGARegistry {

  // TODO ADD YOUR BOARD HERE! - Use uppercase names as keys
  private val registry: Map[String, FPGA] = Map(
    "ZCU104" -> ZCU104,
    "VCU118" -> VCU118
  )

  def getKnownBoards: Seq[String] = registry.keys.toSeq

  /** name -> Board (throws if not found) */
  def n2b(name: String): FPGA = {
    registry.getOrElse(name.toUpperCase, throw new Exception(s"Unknown FPGA board: $name"))
  }

  /** name -> Board */
  def n2bOpt(name: String): Option[FPGA] = {
    registry.get(name.toUpperCase)
  }

  /** Board -> name (throws if not found) */
  def b2n(fpga: FPGA): String = {
    registry.find(_._2 == fpga) match {
      case Some((name, _)) => name
      case None => throw new Exception(s"FPGA '${fpga.friendlyName}' not found in registry")
    }
  }

  /** Board -> name */
  def b2nOpt(fpga: FPGA): Option[String] = {
    registry.find(_._2 == fpga).map(_._1)
  }
}
