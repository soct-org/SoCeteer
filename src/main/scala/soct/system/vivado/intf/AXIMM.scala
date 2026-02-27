package soct.system.vivado.intf

import chisel3.{Bundle, Data}
import chisel3.reflect.DataMirror
import freechips.rocketchip.amba.axi4.AXI4Bundle
import freechips.rocketchip.prci.ClockBundle
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.abstracts.BdPinPort.portToPortName
import soct.system.vivado.{SOCTBdBuilder, SOCTVivado}
import soct.system.vivado.abstracts.{BdIntfPin, BdPinBase, BdPinPort, MapsToPorts, XIntf}

import scala.collection.mutable

case class AXIMM(axiPort: AXI4Bundle, clk: BdPinBase)
                (implicit val bd: SOCTBdBuilder, p: Parameters)
  extends BdIntfPin(portToPortName(axiPort).toUpperCase(), bd.topInstance()) with MapsToPorts with XIntf {

  override def partName: String = "xilinx.com:interface:aximm:1.0"

  override def portMapping: Map[String, Seq[String]] = {
    val ignoredPorts: Set[String] = Set("bits", "user", "echo")

    val portMappings = mutable.Map.empty[String, Seq[String]]
    axiPort.elements.foreach { case (channelName, channel) =>
      DataMirror.collectMembers(channel) {
          case data: Bundle => data.elements
        }
        .foldLeft(Map.empty[String, Data])(_ ++ _)
        .filterNot { case (fieldName, _) => ignoredPorts.contains(fieldName) }
        .foreach { case (fieldName, port) =>
          val portName = portToPortName(port)
          val xilinxName = s"${channelName.toUpperCase}${fieldName.toUpperCase()}"
          val intfString = s"(* X_INTERFACE_INFO = \"$partName $pin $xilinxName\" *)"
          val paramString = if (channel == axiPort.aw && port == axiPort.aw.bits.addr) {
            Some(
              s"(* X_INTERFACE_PARAMETER = \"XIL_INTERFACENAME $pin, PROTOCOL AXI4, DATA_WIDTH ${axiPort.params.dataBits}, CLK_DOMAIN ${clk.pin}, ADDR_WIDTH ${axiPort.params.addrBits}\" *)"
            )
          } else None

          val annotations = paramString match {
            case Some(param) => Seq(param, intfString)
            case None => Seq(intfString)
          }
          if (portMappings.contains(portName)) {
            soct.log.warn(s"Port $portName already has Vivado annotations, overwriting")
          }
          portMappings(portName) = annotations
        }
    }
    portMappings.toMap
  }
}