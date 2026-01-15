package soct.system.vivado.components

import freechips.rocketchip.jtag.JTAGIO
import org.chipsalliance.cde.config.Parameters
import soct.ChiselTop
import soct.system.vivado.SOCTBdBuilder

import scala.collection.mutable


case class JTAGBdXInterface(jtagio: JTAGIO)
                          (implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdXInterface with IsXilinxIP {

  override def partName: String = "xilinx.com:interface:jtag:1.0"

  override def ifName: String = jtagio.instanceName

  override def portMapping: Map[String, Seq[String]] = {
    val portMappings = mutable.Map.empty[String, Seq[String]]

    portMappings.toMap
  }

}