package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.components.BSCAN2JTAG._
import soct.system.vivado.{SOCTBdBuilder, TCLCommands, XilinxDesignException}
import soct.system.vivado.abstracts._
import soct.system.vivado.intf.JTAG

import java.nio.file.{Files, Path}

/**
 * BSCAN to JTAG bridge component for Xilinx FPGAs
 */
case class BSCAN2JTAG()(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp()(bd, p, None) with IsModule with HasAutoConnect[BSCAN2JTAG] {

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

  object S_BSCAN extends SingleIO {
    override def getIO(): BdPinPort = BdIntfPin("S_BSCAN", BSCAN2JTAG.this)
  }


  object M_JTAG extends Source {}

}

object BSCAN2JTAG {
  implicit val a: AutoConnect[BSCAN2JTAG, JTAG] = (comp: BSCAN2JTAG, port: JTAG) => comp.M_JTAG.add(port)
}