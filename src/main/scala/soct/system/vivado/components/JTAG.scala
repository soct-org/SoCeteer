package soct.system.vivado.components

import chisel3.Bool
import freechips.rocketchip.jtag.JTAGIO
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.components.BSCAN2JTAG._
import soct.system.vivado.{SOCTBdBuilder, SOCTVivado, XilinxDesignException}

import java.nio.file.{Files, Path}
import scala.collection.mutable

case class JTAGXIntfPort(jtagio: JTAGIO, TDT: Bool)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends XIntfPort with IsXilinxIP with HasSinkPins {

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

  override protected def getPinImpl[T](source: T): Option[BdIntfPin] = {
    source match {
      case _: BSCAN2JTAG => Some(BdIntfPin(ifName, bd.topInstance()))
      case _ => None
    }
  }
}


/**
 * BSCAN to JTAG bridge component for Xilinx FPGAs
 */
case class BSCAN2JTAG()(implicit bd: SOCTBdBuilder, p: Parameters)
  extends InstantiableBdComp()(bd, p, None) with IsModule with HasSinkPins with SourceForPins {

  /**
   * The reference name of this module - as defined in the collateral files
   */
  override def reference: String = "bscan2jtag"

  override def dumpCollaterals(outDir: Path, dirName: Option[String] = None): Option[Path] = {
    val dest = super.dumpCollaterals(outDir, dirName = Some(friendlyName)).get
    val path = "/bscan/"
    val files = Seq("bscan2jtag.vhdl")
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

  /**
   * Emit the TCL commands to connect this component in the design
   */
  override def connectTclCommands: Seq[String] = {
    sinkPins.map {
      case sink@BdIntfPin(_, _) =>
        s"connect_bd_intf_net [get_bd_intf_pins $instanceName/$jtagIntf] [get_bd_intf_pins $sink]"
      case sink: Any => throw XilinxDesignException(s"Unsupported sink pin type in BSCAN2JTAG: $sink")
    }.toSeq
  }

  override protected def getPinImpl[T](source: T): Option[BdIntfPin] = {
    source match {
      case _: BSCAN => Some(BdIntfPin(BSCAN2JTAG.bscanIntf, this))
      case _ => None
    }
  }
}

object BSCAN2JTAG {
  // Check collateral where the XInterface name is defined
  val bscanIntf = "S_BSCAN"
  val jtagIntf = "JTAG"
}