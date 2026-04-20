package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdBuilder
import soct.system.vivado.abstracts.{BdComp, BdPinIn, BdPinOut, ConnectOps, Xip}

case class RAMShiftReg (width: Int, depth: Int)
                       (implicit bd: SOCTBdBuilder, p: Parameters) extends BdComp with Xip with ConnectOps {

  override def partName: String = "xilinx.com:ip:c_shift_ram:12.0"

  object D extends BdPinIn("D", RAMShiftReg.this)

  object Q extends BdPinOut("Q", RAMShiftReg.this)

  object CLK extends BdPinIn("CLK", RAMShiftReg.this)

  override def defaultProperties: Map[String, String] = Map(
    "CONFIG.Width" -> s"$width",
    "CONFIG.Depth" -> s"$depth"
  )
}
