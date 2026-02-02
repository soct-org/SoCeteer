package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}
import soct.system.vivado.abstracts._

import java.nio.file.{Files, Path}

/**
 * Abstract class for SDIO ports
 */
trait SDIOPort


case class SDIOCDPort()(implicit bd: SOCTBdBuilder, p: Parameters) extends BdVirtualPortI with SDIOPort {
  override def instanceName = "sdio_cd"

  override def ifType: String = "data"
}

case class SDIOClkPort()(implicit bd: SOCTBdBuilder, p: Parameters) extends BdVirtualPortO with SDIOPort {
  override def instanceName = "sdio_clk"

  override def ifType: String = "clk"
}

case class SDIOCmdPort()(implicit bd: SOCTBdBuilder, p: Parameters) extends BdVirtualPortIO with SDIOPort {
  override def instanceName = "sdio_cmd"

  override def ifType: String = "data"
}

case class SDIODataPort()(implicit bd: SOCTBdBuilder, p: Parameters) extends BdVirtualPortIO with SDIOPort {
  override def instanceName = "sdio_data"

  override def ifType: String = "data"

  override def from: Option[String] = Some("3")

  override def to: Option[String] = Some("0")
}


/**
 * SDCard PMOD component for Xilinx FPGAs
 *
 * @param pmodIdx The PMOD index to use
 */
case class SDCardPMOD(pmodIdx: Int)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with IsModule with ConnectOps {

  override def reference: String = "sdc_controller" // The module name inside the collateral files - DO NOT CHANGE

  object ASYNC_RESETN extends BdPinIn("async_resetn", SDCardPMOD.this)

  object CLOCK extends BdPinIn("clock", SDCardPMOD.this)

  object S_AXI_LITE extends BdIntfPin("S_AXI_LITE", SDCardPMOD.this)

  object M_AXI extends BdIntfPin("M_AXI", SDCardPMOD.this)

  private object SDIO_CD extends BdPinIn("sdio_cd", SDCardPMOD.this)

  private object SDIO_CMD extends BdPinInOut("sdio_cmd", SDCardPMOD.this)

  private object SDIO_DATA extends BdPinInOut("sdio_dat", SDCardPMOD.this)

  private object SDIO_CLK extends BdPinOut("sdio_clk", SDCardPMOD.this)

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
  implicit val a: AutoConnect[SDCardPMOD, SDIOPort] = (comp: SDCardPMOD, port: SDIOPort, bd: SOCTBdBuilder) =>
    port match {
      case p: SDIOCDPort => bd.addEdge(p, comp.SDIO_CD) // input
      case p: SDIOCmdPort => bd.addEdge(comp.SDIO_CMD, p) // inout
      case p: SDIODataPort => bd.addEdge(comp.SDIO_DATA, p) // inout
      case p: SDIOClkPort => bd.addEdge(comp.SDIO_CLK, p) // output
      case _ => throw XilinxDesignException(s"SDCardPMOD cannot connect to unknown port type: ${port.getClass}")
    }
}
