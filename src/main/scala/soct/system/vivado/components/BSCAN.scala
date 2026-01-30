package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.components.BSCAN.outPort
import soct.system.vivado.{SOCTBdBuilder, TCLCommands, XilinxDesignException}
import soct.system.vivado.abstracts._


case class BSCAN()(implicit bd: SOCTBdBuilder, p: Parameters) extends BdComp()(bd, p, None)
  with Xip with SourceForSinks {

  override def partName: String = "xilinx.com:ip:debug_bridge:3.0"

  override def defaultProperties: Map[String, String] =  {
    val nSlaves = sinkPins.size
    Map(
      "CONFIG.C_DEBUG_MODE" -> "7", // JTAG-to-AXI, ChipScope, or JTAG-to-JTAG bridge
      "CONFIG.C_USER_SCAN_CHAIN" -> "1", // One user scan chain
      "CONFIG.C_NUM_BS_MASTER" -> s"$nSlaves" // Number of BSCAN slaves
    )
  }

  protected override def connectToSinksImpl: TCLCommands = {
    sinkPins.zipWithIndex.map {
      case (sinkPin: BdIntfPin, i) =>
        val sourcePin = BdIntfPin(outPort(i), this) // TODO check type
        BdPinPort.connect1(sourcePin, sinkPin)
      case _ => throw XilinxDesignException("BSCAN only supports BdIntfPin sink pins")
    }.toSeq
  }
}


object BSCAN {
  def outPort(i: Int): String = s"m${i}_bscan"
}