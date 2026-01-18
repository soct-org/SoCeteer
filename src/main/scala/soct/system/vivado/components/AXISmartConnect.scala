package soct.system.vivado.components

import chisel3.reflect.DataMirror
import chisel3.{Bundle, Data}
import freechips.rocketchip.amba.axi4.AXI4Bundle
import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, SOCTVivado}

import scala.collection.mutable


case class AXIXIntfPort(axiPort: AXI4Bundle)
                       (implicit bd: SOCTBdBuilder, p: Parameters)
extends XIntfPort with IsXilinxIP {

  override def partName: String = "xilinx.com:interface:aximm:1.0"

  override def ifName: String = SOCTVivado.snake(axiPort.instanceName).toUpperCase()

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
          val portName = SOCTVivado.snake(port.instanceName)
          val xilinxName = s"${channelName.toUpperCase}${fieldName.toUpperCase()}"
          val intfString = s"(* X_INTERFACE_INFO = \"$partName $ifName $xilinxName\" *)"
          val paramString = if (channel == axiPort.aw && port == axiPort.aw.bits.addr) {
            Some(
              s"(* X_INTERFACE_PARAMETER = \"XIL_INTERFACENAME $ifName, PROTOCOL AXI4, DATA_WIDTH ${axiPort.params.dataBits}, ADDR_WIDTH ${axiPort.params.addrBits}\" *)"
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


case class AXISmartConnect()(implicit bd: SOCTBdBuilder, p: Parameters, dom: Option[ClockDomain])
  extends InstantiableBdComp with IsXilinxIP {


  override def partName: String = "xilinx.com:ip:smartconnect:1.0"


  /**
   * Emit the TCL commands to connect this component in the design
   */
  override def connectTclCommands: Seq[String] =  Seq.empty
}

