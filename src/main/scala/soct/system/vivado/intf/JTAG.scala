package soct.system.vivado.intf

import chisel3.Bool
import freechips.rocketchip.jtag.JTAGIO
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, SOCTVivado}
import soct.system.vivado.abstracts.{BdIntfPin, HasSinkPins, MapsToPorts, SourceForSinks, XIntf}
import soct.system.vivado.components.BSCAN2JTAG

import scala.collection.mutable

case class JTAG(jtagio: JTAGIO, TDT: Bool)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends MapsToPorts with HasSinkPins with XIntf  {

  override def partName: String = "xilinx.com:interface:jtag:1.0"

  def ifName: String = "JTAG"

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

  override protected def getPinImpl(source: SourceForSinks): Option[BdIntfPin] = {
    source match {
      case _: BSCAN2JTAG => Some(BdIntfPin(ifName, bd.topInstance()))
      case _ => None
    }
  }
}