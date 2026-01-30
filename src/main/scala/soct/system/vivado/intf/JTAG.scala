package soct.system.vivado.intf

import chisel3.Bool
import freechips.rocketchip.jtag.JTAGIO
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, SOCTVivado}
import soct.system.vivado.abstracts.{BdComp, BdIntfPin, BdPinPort, HasSinkPins, KeyForSink, MapsToPorts, SourceForSinks, XIntf}
import soct.system.vivado.components.BSCAN2JTAG
import soct.system.vivado.intf.JTAG.jtagIntf

import scala.collection.mutable

case class JTAG(jtagio: JTAGIO, TDT: Bool)(implicit bd: SOCTBdBuilder, p: Parameters)
  extends MapsToPorts with HasSinkPins with XIntf  {

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
      val portName = SOCTVivado.snake(port.instanceName)
      val intfString = s"(* X_INTERFACE_INFO = \"$partName $jtagIntf $xilinxName\" *)"
      portMappings(portName) = Seq(intfString)
    }
    portMappings.toMap
  }

  override protected def getPinImpl(source: SourceForSinks, sinkKey: KeyForSink): Option[BdPinPort] = {
    sinkKey match {
      case JTAG.Keys.BSCAN2JTAG => Some(JTAG.Keys.BSCAN2JTAG.getPin(bd.topInstance())())
      case _ => None
    }
  }
}

object JTAG {
  object Keys {
    object BSCAN2JTAG extends KeyForSink {
      override def getPin[T <: BdComp](comp: T): () => BdPinPort = () => BdIntfPin(jtagIntf, comp)
    }
  }

  private val jtagIntf = "JTAG"
}