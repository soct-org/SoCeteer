package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}
import soct.system.vivado.abstracts._

import java.nio.file.{Files, Path}

/**
 * Base class for SDIO ports
 */
abstract class SDIOPort()(implicit bd: SOCTBdBuilder, p: Parameters) extends BdPort with HasIO {
  override def getIO: BdPinPort = this
}


case class SDIOCDPort()(implicit bd: SOCTBdBuilder, p: Parameters) extends SDIOPort  {
  override def instanceName = "sdio_cd"

  override def ifType: String = "data"

  override def dir: String = "I"
}

case class SDIOClkPort()(implicit bd: SOCTBdBuilder, p: Parameters) extends SDIOPort {
  override def instanceName = "sdio_clk"

  override def ifType: String = "clk"

  override def dir: String = "O"
}

case class SDIOCmdPort()(implicit bd: SOCTBdBuilder, p: Parameters) extends SDIOPort {
  override def instanceName = "sdio_cmd"

  override def ifType: String = "data"

  override def dir: String = "IO"
}

case class SDIODataPort()(implicit bd: SOCTBdBuilder, p: Parameters) extends SDIOPort {
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
  extends BdComp with IsModule with AutoClockAndReset with HasAutoConnect[SDCardPMOD] {

  override def reference: String = "sdc_controller" // The module name inside the collateral files - DO NOT CHANGE

  override def clockInPorts: () => Seq[BdPinPort] = () => Seq(BdPin("clock", this))

  override def resetNInPorts: () => Seq[BdPinPort] = () => Seq(BdPin("async_resetn", this))

  override def resetInPorts: () => Seq[BdPinPort] = () => Seq.empty

  object SDIO_CD extends SingleIO {override def getIO: BdPinPort = BdPin("sdio_cd", SDCardPMOD.this)}

  object SDIO_CMD extends SingleIO {override def getIO: BdPinPort = BdPin("sdio_cmd", SDCardPMOD.this)}

  object SDIO_DATA extends SingleIO {override def getIO: BdPinPort = BdPin("sdio_dat", SDCardPMOD.this)}

  object SDIO_CLK extends SingleIO {override def getIO: BdPinPort = BdPin("sdio_clk", SDCardPMOD.this)}

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
}

object SDCardPMOD {
  implicit val a: AutoConnect[SDCardPMOD, SDIOPort] = (comp: SDCardPMOD, port: SDIOPort) =>
    port match {
      case p: SDIOCDPort => comp.SDIO_CD.connect(p)
      case p: SDIOCmdPort => comp.SDIO_CMD.connect(p)
      case p: SDIODataPort => comp.SDIO_DATA.connect(p)
      case p: SDIOClkPort => comp.SDIO_CLK.connect(p)
      case _ => throw XilinxDesignException(s"SDCardPMOD cannot connect to unknown port type: ${port.getClass}")
    }

}
