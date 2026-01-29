package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.components.BSCAN2JTAG._
import soct.system.vivado.{SOCTBdBuilder, TCLCommands, XilinxDesignException}
import soct.system.vivado.abstracts._

import java.nio.file.{Files, Path}

/**
 * BSCAN to JTAG bridge component for Xilinx FPGAs
 */
case class BSCAN2JTAG()(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp()(bd, p, None) with IsModule with HasSinkPins with SourceForSinks {

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
  protected override def connectToSinksImpl: TCLCommands = {
    val source = BdIntfPin(jtagIntf, this)
    BdPinPort.connect(source, sinkPins)
  }

  override protected def getPinImpl(source: SourceForSinks): Option[BdIntfPin] = {
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