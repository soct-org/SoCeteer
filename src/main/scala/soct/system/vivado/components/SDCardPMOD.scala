package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, StringToTCLCommand, TCLCommands, XilinxDesignException}
import soct.system.vivado.abstracts._
import soct.system.vivado.misc.DTSInfo

import java.nio.file.{Files, Path}


case class SDIOCDPort(override val pmodPort: Int)
                     (implicit bd: SOCTBdBuilder, p: Parameters) extends BdVirtualPortI with WantsPMODPins {
  override def portName: String = "sdio_cd"

  override def ifType: String = "data"

  override def pmodPins: Seq[BasePMODPin] = Seq(DigilentPMODPin(9))
}

case class SDIOClkPort(override val pmodPort: Int)
                      (implicit bd: SOCTBdBuilder, p: Parameters) extends BdVirtualPortO with WantsPMODPins {
  override def portName: String = "sdio_clk"

  override def ifType: String = "clk"

  override def pmodPins: Seq[BasePMODPin] = Seq(DigilentPMODPin(4))
}

case class SDIOCmdPort(override val pmodPort: Int)
                      (implicit bd: SOCTBdBuilder, p: Parameters) extends BdVirtualPortIO with WantsPMODPins {
  override def portName: String = "sdio_cmd"

  override def ifType: String = "data"

  override def pmodPins: Seq[BasePMODPin] = Seq(DigilentPMODPin(2))
}

case class SDIODataPort(override val pmodPort: Int)
                       (implicit bd: SOCTBdBuilder, p: Parameters) extends BdVirtualPortIO with WantsPMODPins {
  override def portName: String = "sdio_data"

  override def ifType: String = "data"

  override def from: Option[String] = Some("3")

  override def to: Option[String] = Some("0")

  override def pmodPins: Seq[BasePMODPin] = Seq(3, 7, 8, 1).map(DigilentPMODPin)
}


/**
 * SDCard PMOD component for Xilinx FPGAs. This component interfaces with an SD card through a PMOD connector
 * and provides an AXI4-Lite slave interface for control and an AXI4 master interface for data transfer.
 * It also includes an interrupt output and a card detect input.
 *
 * Reference:
 * https://digilent.com/reference/pmod/pmodsd/reference-manual
 *
 */
case class SDCardPMOD(
                       override val dtsInfo: DTSInfo,
                       override val getAxiMasterPin: BdIntfPin,
                       override val getAxiSlavePins: Seq[(BdIntfPin, String)]
                     )(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with IsModule with ConnectOps with HasAxiSlave with HasAxiMaster with HasDTSInfo {

  override def reference: String = "sdc_controller" // The module name inside the collateral files - DO NOT CHANGE

  object ASYNC_RESETN extends BdPinIn("async_resetn", SDCardPMOD.this)

  object CLOCK extends BdPinIn("clock", SDCardPMOD.this)

  override def S_AXI: BdIntfPin = new BdIntfPin("S_AXI_LITE", SDCardPMOD.this)

  override def M_AXI: BdIntfPin = new BdIntfPin("M_AXI", SDCardPMOD.this)

  object INTERRUPT extends BdPinOut("interrupt", SDCardPMOD.this)

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

  override def assignAddrTcl: TCLCommands = {
    val regs = dtsInfo.regs
    if (regs.size != 1) {
      throw XilinxDesignException(s"SDCardPMOD DTSInfo must have exactly one reg entry, but found ${regs.size}")
    }
    val (_, offset, size) = regs.head
    val slaveConnects = Seq(
      s"assign_bd_address -offset $offset -range $size -target_address_space [get_bd_addr_spaces ${getAxiMasterPin.ref}] [get_bd_addr_segs ${S_AXI.ref}/reg0]".tcl
    )

    val masterConnects = getAxiSlavePins.map { case (pin, regName) =>
      // We use a fixed offset of 0 and a large range to cover the entire address space of the master interface
      s"assign_bd_address -offset 0x00000000 -range 0x000100000000 -target_address_space [get_bd_addr_spaces ${M_AXI.ref}] [get_bd_addr_segs ${pin.ref}/$regName]".tcl
    }

    masterConnects ++ slaveConnects
  }
}

object SDCardPMOD {
  implicit val a: AutoConnect[SDCardPMOD, BdVirtualPort] = (comp: SDCardPMOD, port: BdVirtualPort, bd: SOCTBdBuilder) =>
    port match {
      case p: SDIOCDPort => bd.addEdge(p, comp.SDIO_CD) // input
      case p: SDIOCmdPort => bd.addEdge(comp.SDIO_CMD, p) // inout
      case p: SDIODataPort => bd.addEdge(comp.SDIO_DATA, p) // inout
      case p: SDIOClkPort => bd.addEdge(comp.SDIO_CLK, p) // output
      case _ => throw XilinxDesignException(s"SDCardPMOD cannot connect to unknown port type: ${port.getClass}")
    }
}
