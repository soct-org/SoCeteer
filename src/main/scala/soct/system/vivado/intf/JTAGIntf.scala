package soct.system.vivado.intf

import chisel3.Bool
import freechips.rocketchip.jtag.JTAGIO
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.abstracts.BdPinPort.portToPortName
import soct.system.vivado.{SOCTBdBuilder, SOCTVivado}
import soct.system.vivado.abstracts.{BdIntfPin, BdPinPort, MapsToPorts, XIntf}

import scala.collection.mutable

case class JTAGIntf(jtagio: JTAGIO, TDT: Bool)(implicit val bd: SOCTBdBuilder, p: Parameters)
  extends BdIntfPin("JTAG", bd.topInstance()) with MapsToPorts with XIntf {

  override def partName: String = "xilinx.com:interface:jtag:1.0"

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
      val portName = portToPortName(port)
      val intfString = s"(* X_INTERFACE_INFO = \"$partName $pin $xilinxName\" *)"
      portMappings(portName) = Seq(intfString)
    }
    portMappings.toMap
  }
}