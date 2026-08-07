package soct.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.vivado.components.BSCAN2JTAG._
import soct.vivado.{SOCTBdBuilder, TCLCommands, VivadoDesignException}
import soct.vivado.abstracts._
import soct.vivado.intf.JTAGIntf

import java.nio.file.{Files, Path}

/**
 * BSCAN to JTAG bridge component for Xilinx FPGAs
 */
case class BSCAN2JTAG()(implicit bd: SOCTBdBuilder, p: Parameters)
  extends BdComp with IsModule with ConnectOps {

  /**
   * The reference name of this module - as defined in the collateral files
   */
  override def reference: String = "bscan2jtag"

  /**
   * Copy the `bscan2jtag.vhdl` collateral from the classpath resources next to the design sources.
   *
   * @throws soct.vivado.VivadoDesignException if the bundled VHDL resource is missing
   */
  override def dumpCollaterals(outDir: Path, dirName: Option[String] = None): Option[Path] = {
    val dest = super.dumpCollaterals(outDir, dirName = Some(friendlyName)).get
    val path = "/bscan/"
    val files = Seq("bscan2jtag.vhdl")
    files.foreach(file => {
      val contentOpt = soct.getResource(path + file)
      if (contentOpt.isEmpty) {
        throw VivadoDesignException(s"Could not find BSCAN2JTAG collateral file: $file")
      }
      val outFile = dest.resolve(file).toFile
      Files.write(outFile.toPath, contentOpt.get.getBytes)
    })
    Some(dest)
  }

  object S_BSCAN extends BdIntfPin("S_BSCAN", BSCAN2JTAG.this)
  object JTAG extends BdIntfPin("JTAG", BSCAN2JTAG.this)
}

/** Implicit connect rules: `<->` hooks the bridge's JTAG side to a [[soct.vivado.intf.JTAGIntf]]. */
object BSCAN2JTAG {
  implicit val bscan2jtagToJtag: AutoConnect[BSCAN2JTAG, JTAGIntf] = (comp: BSCAN2JTAG, port: JTAGIntf, bd: SOCTBdBuilder) =>
    bd.addEdge(comp.JTAG, port)
}