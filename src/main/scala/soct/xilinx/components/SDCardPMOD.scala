package soct.xilinx.components

import freechips.rocketchip.subsystem.{CanHaveMasterAXI4MMIOPort, CanHaveSlaveAXI4Port}
import org.chipsalliance.cde.config
import soct.{ChiselTop, HasXilinxFPGA}
import soct.xilinx.XilinxDesignException

import java.nio.file.{Files, Path}


case class SDIOCDPort() extends BdPort {
  override val INTERFACE_NAME = "sdio_cd"

  override val ifType: String = "data"

  override val dir: String = "I"
}

case class SDIOClkPort() extends BdPort {
  override val INTERFACE_NAME = "sdio_clk"

  override val ifType: String = "clk"

  override val dir: String = "O"
}

case class SDIOCmdPort() extends BdPort {
  override val INTERFACE_NAME = "sdio_cmd"

  override val ifType: String = "data"

  override val dir: String = "IO"
}

case class SDIODataPort() extends BdPort {
  override val INTERFACE_NAME = "sdio_data"

  override val ifType: String = "data"

  override val dir: String = "IO"

  override val from: Option[String] = Some("3")

  override val to: Option[String] = Some("0")
}


/**
 * SDCard PMOD component for Xilinx FPGAs
 *
 * @param pmodIdx  The PMOD index to use
 * @param cdPort   The card detect port
 * @param clkPort  The clock port
 * @param cmdPort  The command port
 * @param dataPort The data port
 */
case class SDCardPMOD(pmodIdx: Int,
                      cdPort: SDIOCDPort,
                      clkPort: SDIOClkPort,
                      cmdPort: SDIOCmdPort,
                      dataPort: SDIODataPort
                     ) extends InstantiableComponent with IsModule {

  override val reference: String = "sdc_controller" // The module name inside the collateral files - DO NOT CHANGE


  override def checkAvailable(top: ChiselTop)(implicit p: config.Parameters): Unit = {
    super.checkAvailable(top)
    val fpga = p(HasXilinxFPGA).get

    if (!fpga.portsPMOD.contains(pmodIdx)) {
      throw XilinxDesignException(s"PMOD index $pmodIdx is not available on the selected FPGA")
    }

    // The SD controller requires both a master MMIO port and a slave AXI4 port to function:
    val hasMMIOPort = top.fold(_ => false, cls => classOf[CanHaveMasterAXI4MMIOPort].isAssignableFrom(cls))
    if (!hasMMIOPort) throw XilinxDesignException("Top must mix in CanHaveMasterAXI4MMIOPort")
    val hasSlaveMMIOPort = top.fold(_ => false, cls => classOf[CanHaveSlaveAXI4Port].isAssignableFrom(cls))
    if (!hasSlaveMMIOPort) throw XilinxDesignException("Top must mix in CanHaveSlaveAXI4Port")

    // TODO continue
  }

  override def dumpCollaterals(outDir: Path, createDir: Boolean): Option[Path] = {
    val dest = super.dumpCollaterals(outDir, createDir = true).get
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
