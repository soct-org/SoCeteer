package soct.xilinx.components

import freechips.rocketchip.subsystem.{CanHaveMasterAXI4MMIOPort, CanHaveSlaveAXI4Port}
import org.chipsalliance.cde.config
import soct.{ChiselTop, HasXilinxFPGA}
import soct.xilinx.XilinxDesignException

import java.nio.file.{Files, Path}


class SDCmdInterface extends XilinxIPComponent with XilinxBdIntfPort {
  lazy val INTERFACE_NAME = "sd_cmd"

  val mode: String = "Master"

  override val partName: String = "xilinx.com:interface:ddr4_rtl:1.0"
}


/**
 * SD Card controller connected via PMOD interface
 * @param pmodIdx Which PMOD port to use (default: 0)
 */
case class SDCardPMOD(pmodIdx: Int = 0) extends InstantiableComponent with IsModule {

  override val reference: String = "sdc_controller"


  override def checkAvailable(top: ChiselTop)(implicit p: config.Parameters): Unit = {
    val fpgaOpt = p(HasXilinxFPGA)
    if (fpgaOpt.isEmpty) {
      throw XilinxDesignException("Adding SDCardController requires the design to run on a Xilinx FPGA")
    }

    if (!fpgaOpt.get.hasPMOD) {
      throw XilinxDesignException(s"Adding SDCardController requires the target FPGA (${fpgaOpt.get.friendlyName}) to have PMOD support")
    } else {
      soct.log.info(s"Adding SDCardController to FPGA (${fpgaOpt.get.friendlyName}) that requires a PMOD to SDCard adapter. " +
        s"If you have not done so, please connect an appropriate adapter to the PMOD port.")
    }

    // The SD controller requires both a master MMIO port and a slave AXI4 port to function:
    val hasMMIOPort = top.fold(
      _   => false,
      cls => classOf[CanHaveMasterAXI4MMIOPort].isAssignableFrom(cls)
    )
    if (!hasMMIOPort)
      throw XilinxDesignException("Top must mix in CanHaveMasterAXI4MMIOPort")
    val hasSlaveMMIOPort = top.fold(
      _   => false,
      cls => classOf[CanHaveSlaveAXI4Port].isAssignableFrom(cls)
    )
    if (!hasSlaveMMIOPort)
      throw XilinxDesignException("Top must mix in CanHaveSlaveAXI4Port")


  }

  override def dumpCollaterals(outDir: Path) : Unit = {
    val path = "/sdc/"
    val files = Seq(
      "axi_sdc_controller.v",
      "sd_cmd_master.v",
      "sd_cmd_serial_host.v",
      "sd_data_master.v",
      "sd_data_serial_host.v",
      "sd_defines.h"
    )
    files.foreach( file => {
      val contentOpt = soct.getResource(path + file)
      if (contentOpt.isEmpty) {
        throw XilinxDesignException(s"Could not find SDCardController collateral file: $file")
      }
      val outFile = outDir.resolve(file).toFile
      Files.write(outFile.toPath, contentOpt.get.getBytes)
    })
  }

}
