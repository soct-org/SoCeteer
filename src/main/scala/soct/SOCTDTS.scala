package soct

import scala.util.matching.Regex
import scala.util.chaining.scalaUtilChainingOps

object DTSExtractor {

  def extractMarch(dts: String, key: String = "riscv,isa", invalid: Seq[String] = Seq("b", "_xrocket")): String = {
    val pattern = s"""(?s)$key = "(.*?)"""".r
    val matches = pattern.findAllMatchIn(dts).toSeq
    if (matches.isEmpty) {
      throw new IllegalArgumentException(s"Key '$key' not found in DTS")
    }
    var march = matches.head.group(1)
    invalid.foreach(invalidKey => march = march.replaceFirst(invalidKey, ""))
    if (march.isEmpty) {
      throw new IllegalArgumentException(s"Key '$key' not found in DTS")
    }
    march
  }
}

object DTSModifier {

  // Replace 32-bit memory address range
  private def updateMemoryRange32(dts: String, memoryAddrRange32: String): String = {
    val pattern: Regex = """reg = <0x80000000 *0x.*?>""".r
    pattern.replaceAllIn(dts, s"reg = <$memoryAddrRange32>")
  }

  // Replace 64-bit memory address range
  private def updateMemoryRange64(dts: String, memoryAddrRange64: String): String = {
    val pattern: Regex = """reg = <0x0 0x80000000 *0x.*?>""".r
    pattern.replaceAllIn(dts, s"reg = <$memoryAddrRange64>")
  }

  // Update clock frequency
  private def updateClockFrequency(dts: String, rocketClockFreq: String): String = {
    val pattern: Regex = """clock-frequency = <[0-9]+>""".r
    pattern.replaceAllIn(dts, s"clock-frequency = <$rocketClockFreq>")
  }

  // Update timebase frequency
  private def updateTimebaseFrequency(dts: String, rocketTimebaseFreq: String): String = {
    val pattern: Regex = """timebase-frequency = <[0-9]+>""".r
    pattern.replaceAllIn(dts, s"timebase-frequency = <$rocketTimebaseFreq>")
  }

  // Update Ethernet MAC address (if defined)
  private def updateEtherMacAddress(dts: String, etherMac: Option[String]): String = {
    etherMac match {
      case Some(mac) =>
        val pattern: Regex = """local-mac-address = \[.*?]""".r
        pattern.replaceAllIn(dts, s"local-mac-address = [$mac]")
      case None => dts
    }
  }

  // Update Ethernet PHY mode (if defined)
  private def updateEtherPhyMode(dts: String, etherPhy: Option[String]): String = {
    etherPhy match {
      case Some(phy) =>
        val pattern: Regex = """phy-mode = ".*?" """.r
        pattern.replaceAllIn(dts, s"""phy-mode = "$phy" """)
      case None => dts
    }
  }

  // Remove unwanted interrupt settings
  private def removeInterruptsExtended(dts: String): String = {
    val pattern: Regex = """\s*interrupts-extended = <&.*? 65535>;\s*\n?""".r
    pattern.replaceAllIn(dts, "")
  }

  // Apply all modifications
  def modifyDTS(dts: String, params: BoardParams): String = {
    val (rocketClockFreq, rocketTimebaseFreq) = params.calculateFrequencies()
    val (memoryAddrRange32, memoryAddrRange64) = params.calculateMemoryAddressRange()

    val updatedDTS = updateMemoryRange32(dts, memoryAddrRange32)
      .pipe(updateMemoryRange64(_, memoryAddrRange64))
      .pipe(updateClockFrequency(_, rocketClockFreq.toString))
      .pipe(updateTimebaseFrequency(_, rocketTimebaseFreq.toString))
      .pipe(updateEtherMacAddress(_, params.ETHER_MAC))
      .pipe(updateEtherPhyMode(_, params.ETHER_PHY))
      .pipe(removeInterruptsExtended)

    updatedDTS
  }
}