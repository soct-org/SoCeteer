package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.components.SDCardPMOD._
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}

import java.nio.file.{Files, Path}


case class SDIOCDPort()(implicit bd: SOCTBdBuilder, p: Parameters) extends VirtualPort {
  override def instanceName = "sdio_cd"

  override def ifType: String = "data"

  override def dir: String = "I"
}

case class SDIOClkPort()(implicit bd: SOCTBdBuilder, p: Parameters) extends VirtualPort {
  override def instanceName = "sdio_clk"

  override def ifType: String = "clk"

  override def dir: String = "O"
}

case class SDIOCmdPort()(implicit bd: SOCTBdBuilder, p: Parameters) extends VirtualPort {
  override def instanceName = "sdio_cmd"

  override def ifType: String = "data"

  override def dir: String = "IO"
}

case class SDIODataPort()(implicit bd: SOCTBdBuilder, p: Parameters) extends VirtualPort {
  override def instanceName = "sdio_data"

  override def ifType: String = "data"

  override def dir: String = "IO"

  override def from: Option[String] = Some("3")

  override def to: Option[String] = Some("0")
}


/**
 * SDCard PMOD component for Xilinx FPGAs
 *
 * @param pmodIdx  The PMOD index to use
 */
case class SDCardPMOD(pmodIdx: Int)(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain])
  extends InstantiableBdComp with IsModule with AutoConnect with HasSinkPins {

  override def reference: String = "sdc_controller" // The module name inside the collateral files - DO NOT CHANGE

  override def clockInPorts: Seq[BdPinBase] = Seq(BdPin(clock, this))

  override def resetNInPorts: Seq[BdPinBase] = Seq(BdPin(resetN, this))

  override def dumpCollaterals(outDir: Path, dirName: Option[String] = None): Option[Path] = {
    val dest = super.dumpCollaterals(outDir, dirName = Some(friendlyName)).get
    val path = "/sdc/"
    val files = Seq(
      "axi_sdc_controller.v",
      "sd_cmd_master.v",
      "sd_cmd_serial_host.v",
      "sd_data_master.v",
      "sd_data_serial_host.v",
      "sd_defines.h"
    )
    files.foreach(file => {
      val contentOpt = soct.getResource(path + file)
      if (contentOpt.isEmpty) {
        throw XilinxDesignException(s"Could not find SDCardController collateral file: $file")
      }
      val outFile = dest.resolve(file).toFile
      Files.write(outFile.toPath, contentOpt.get.getBytes)
    })
    Some(dest)
  }

  protected override def getPinImpl(source: SourceForSinks): Option[BdPinBase] = {
    source match {
      case _: SDIOCDPort => Some(BdPin(sdioCD, this))
      case _: SDIOCmdPort => Some(BdPin(sdioCMD, this))
      case _: SDIODataPort => Some(BdPin(sdioDat, this))
      case _: SDIOClkPort => Some(BdPin(sdioClk, this))
      case _ => None
    }
  }
}

object SDCardPMOD {
  private val sdioCD = "sdio_cd"

  private val sdioCMD = "sdio_cmd"

  private val sdioDat = "sdio_dat"

  private val sdioClk = "sdio_clk"

  private val clock = "clock"

  private val resetN = "async_resetn"
}
