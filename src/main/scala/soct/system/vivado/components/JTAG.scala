package soct.system.vivado.components

import chisel3.{Bool, Data}
import freechips.rocketchip.jtag.JTAGIO
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, SOCTVivado, XilinxDesignException}

import java.nio.file.{Files, Path}
import scala.collection.mutable

case class JTAGXIntfPort(jtagio: JTAGIO, TDT: Bool)
                        (implicit bd: SOCTBdBuilder, p: Parameters)
  extends XIntfPort with IsXilinxIP {

  override def partName: String = "xilinx.com:interface:jtag:1.0"

  override def ifName: String = "JTAG"

  override def portMapping: Map[String, Seq[String]] = {
    val portMappings = mutable.Map.empty[String, Seq[String]]
    val ports2Xilinx = Map(
      jtagio.TCK -> "TCK",
      jtagio.TMS -> "TMS",
      jtagio.TDI -> "TD_I",
      jtagio.TDO.data -> "TD_O",
      TDT -> "TD_T"
    )
    ports2Xilinx.foreach { case (port, xilinxName) =>
      val portName = SOCTVivado.snake(port.instanceName)
      val intfString = s"(* X_INTERFACE_INFO = \"$partName $ifName $xilinxName\" *)"
      portMappings(portName) = Seq(intfString)
    }
    portMappings.toMap
  }
}


/**
 * BSCAN to JTAG bridge component for Xilinx FPGAs
 */
case class BSCAN2JTAG()(implicit bd: SOCTBdBuilder, p: Parameters) extends InstantiableBdComp with IsModule {

  /**
   * The reference name of this module - as defined in the collateral files
   */
  override def reference: String = "bscan2jtag"

  val bscanIntf = "S_BSCAN" // Check collateral where the XInterface name is defined

  private val jtagIntf = "JTAG" // Check collateral where the XInterface name is defined

  override def dumpCollaterals(outDir: Path, createDir: Boolean): Option[Path] = {
    val dest = super.dumpCollaterals(outDir, createDir = true).get
    val path = "/bscan/"
    val files = Seq(
      "bscan2jtag.vhdl"
    )
    files.foreach(file => {
      val contentOpt = soct.getResource(path + file)
      if (contentOpt.isEmpty) {
        throw XilinxDesignException(s"Could not find BSCAN2JTAG collateral file: $file")
      }
      val outFile = dest.resolve(file).toFile
      Files.write(outFile.toPath, contentOpt.get.getBytes)
    })
    Some(dest)
  }

  private def validReceivers: Seq[JTAGXIntfPort] = receivers.collect {
    case jtag: JTAGXIntfPort => jtag
  }.toSeq

  /**
   * Emit the TCL commands to connect this component in the design
   */
  override def connectTclCommands: Seq[String] = {
    val topInst = bd.topInstance().instanceName
    validReceivers.map { jtag =>
      s"connect_bd_intf_net [get_bd_intf_pins $instanceName/$jtagIntf] [get_bd_intf_pins $topInst/${jtag.ifName}]"
    }
  }
}